package au.com.agl.arc.load

import java.lang._
import java.net.URI
import scala.collection.JavaConverters._
import java.util.concurrent.Executors
import java.time.Duration

import com.microsoft.azure.eventhubs.ConnectionStringBuilder
import com.microsoft.azure.eventhubs.EventData
import com.microsoft.azure.eventhubs.EventHubClient
import com.microsoft.azure.eventhubs.PartitionSender
import com.microsoft.azure.eventhubs.EventHubException
import com.microsoft.azure.eventhubs.EventDataBatch
import com.microsoft.azure.eventhubs.impl.RetryExponential

import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._

import scala.io.Source

import au.com.agl.arc.api.API._
import au.com.agl.arc.util._

object AzureEventHubsLoad {

  def load(load: AzureEventHubsLoad)(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger): Unit = {
    import spark.implicits._
    val startTime = System.currentTimeMillis() 

    val signature = "AzureEventHubsLoad requires inputView to be dataset with [value: string] signature."

    val stageDetail = new java.util.HashMap[String, Object]()
    stageDetail.put("type", load.getType)
    stageDetail.put("name", load.name)
    stageDetail.put("inputView", load.inputView)  
    stageDetail.put("namespaceName", load.namespaceName)  
    stageDetail.put("eventHubName", load.eventHubName)  
    stageDetail.put("sharedAccessSignatureKeyName", load.sharedAccessSignatureKeyName)  

    logger.info()
      .field("event", "enter")
      .map("stage", stageDetail)      
      .log()

    val df = spark.table(load.inputView)     

    if (df.schema.length != 1 || df.schema(0).dataType != StringType) {
        throw new Exception(s"${signature} inputView '${load.inputView}' has ${df.schema.length} columns of type [${df.schema.map(f => f.dataType.simpleString).mkString(", ")}].") with DetailException {
        override val detail = stageDetail          
      }      
    }     

    val repartitionedDF = load.numPartitions match {
      case Some(partitions) => {
        stageDetail.put("numPartitions", Integer.valueOf(partitions))
        df.repartition(partitions)
      }
      case None => {
        stageDetail.put("numPartitions", Integer.valueOf(df.rdd.getNumPartitions))
        df
      }
    }      

    // initialise statistics accumulators or reset if they exist
    val recordAccumulator = spark.sparkContext.longAccumulator
    val batchAccumulator = spark.sparkContext.longAccumulator
    recordAccumulator.reset
    batchAccumulator.reset

    try {
      repartitionedDF.foreachPartition(partition => {
        // establish connection
        val connStr = { new ConnectionStringBuilder()
          .setNamespaceName(load.namespaceName)
          .setEventHubName(load.eventHubName)
          .setSasKeyName(load.sharedAccessSignatureKeyName)
          .setSasKey(load.sharedAccessSignatureKey)
        }
        val executorService = Executors.newSingleThreadExecutor()
        val retryPolicy = new RetryExponential(
          Duration.ofSeconds(load.retryMinBackoff.getOrElse(0)),    // DEFAULT_RETRY_MIN_BACKOFF = 0
          Duration.ofSeconds(load.retryMaxBackoff.getOrElse(30)),   // DEFAULT_RETRY_MAX_BACKOFF = 30
          load.retryCount.getOrElse(10),                            // DEFAULT_MAX_RETRY_COUNT = 10
          "Custom")                                                 // DEFAULT_RETRY = "Default"
        val eventHubClient = EventHubClient.createSync(connStr.toString(), retryPolicy, executorService)

        // reusable batch
        var eventBatch = eventHubClient.createBatch
        batchAccumulator.add(1)
        
        // send each message via shared connection
        try {
          partition.foreach(row => {
            // create event
            val jsonBytes = row.getString(0).getBytes("UTF-8")
            val event = EventData.create(jsonBytes)

            // if cannot add to eventBatch send payload then reset batch and add item
            if (!eventBatch.tryAdd(event)) {
              eventHubClient.sendSync(eventBatch)
              eventBatch = eventHubClient.createBatch
              eventBatch.tryAdd(event)

              batchAccumulator.add(1)
            }

            recordAccumulator.add(1)
          })

          // if there are events in the buffer send them
          if (eventBatch.getSize > 0) {
            eventHubClient.sendSync(eventBatch)
          }          
        } finally {
          eventHubClient.closeSync
          executorService.shutdown
        }          
      })
    } catch {
      case e: Exception => throw new Exception(e) with DetailException {
        stageDetail.put("records", Long.valueOf(recordAccumulator.value)) 
        stageDetail.put("batches", Long.valueOf(batchAccumulator.value)) 
        override val detail = stageDetail          
      }
    }

    stageDetail.put("records", Long.valueOf(recordAccumulator.value)) 
    stageDetail.put("batches", Long.valueOf(batchAccumulator.value)) 

    logger.info()
      .field("event", "exit")
      .field("duration", System.currentTimeMillis() - startTime)
      .map("stage", stageDetail)      
      .log()   
  }
}