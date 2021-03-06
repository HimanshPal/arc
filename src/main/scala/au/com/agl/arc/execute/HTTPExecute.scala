package au.com.agl.arc.execute

import scala.collection.JavaConverters._

import org.apache.http.client.methods.HttpPost
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.entity.StringEntity

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import org.apache.spark.sql._

import au.com.agl.arc.api.API._
import au.com.agl.arc.util._

object HTTPExecute {

  def execute(exec: HTTPExecute)(implicit spark: SparkSession, logger: au.com.agl.arc.util.log.logger.Logger): Int = {
    val startTime = System.currentTimeMillis() 

    val maskedHeaders = HTTPUtils.maskHeaders(exec.headers)

    val stageDetail = new java.util.HashMap[String, Object]()
    stageDetail.put("type", exec.getType)
    stageDetail.put("name", exec.name)
    stageDetail.put("uri", exec.uri.toString)      
    stageDetail.put("headers", maskedHeaders.asJava)

    logger.info()
      .field("event", "enter")
      .map("stage", stageDetail)      
      .log()   

    val uri = exec.uri.toString

    val client = HttpClientBuilder.create.build
    val post = new HttpPost(uri)

    // add headers
    for ((k,v) <- exec.headers) {
      post.addHeader(k,v) 
    }

    // create json payload
    if (exec.payloads.size > 0) {
      val mapper = new ObjectMapper()
      mapper.registerModule(DefaultScalaModule)
      val json = mapper.writeValueAsString(exec.payloads)

      // add payload
      val stringEntity = new StringEntity(json)
      post.setEntity(stringEntity)
    }
    
    // send the request
    val response = client.execute(post)
    response.close 

    val responseMap = new java.util.HashMap[String, Object]()
    responseMap.put("statusCode", new java.lang.Integer(response.getStatusLine.getStatusCode))
    responseMap.put("reasonPhrase", response.getStatusLine.getReasonPhrase)   
    stageDetail.put("response", responseMap)   

    // verify status code is correct
    val validStatusCodes = exec.validStatusCodes match {
      case Some(value) => value
      case None => 200 :: 201 :: 202 :: Nil
    }
    if (!validStatusCodes.contains(response.getStatusLine.getStatusCode)) {
      throw new Exception(s"""HTTPExecute expects a response StatusCode in [${validStatusCodes.mkString(", ")}] but server responded with ${response.getStatusLine.getStatusCode} (${response.getStatusLine.getReasonPhrase}).""") with DetailException {
        override val detail = stageDetail
      }
    }    

    logger.info()
      .field("event", "exit")
      .field("duration", System.currentTimeMillis() - startTime)
      .map("stage", stageDetail)      
      .log()   

    response.getStatusLine.getStatusCode  
  }
}


