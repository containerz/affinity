/*
 * Copyright 2016 Michal Harish, michal.harish@gmail.com
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.amient.affinity.core.serde.avro.schema


import java.io.DataOutputStream
import java.net.{HttpURLConnection, URL}

import akka.actor.ExtendedActorSystem
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import io.amient.affinity.core.serde.avro.AvroSerde
import org.apache.avro.Schema

import scala.collection.JavaConverters._

object CfAvroSchemaRegistry {
  final val CONFIG_CF_REGISTRY_URL_BASE = "affinity.confluent-schema-registry.url.base"
}
/**
  * Confluent Schema Registry provider and serde
  * This provider uses Confluent Schema Registry but doesn't use the topic-key and topic-value subjects.
  * Instead a fully-qualified name of the class is the subject.
  */

class CfAvroSchemaRegistry(system: ExtendedActorSystem) extends AvroSerde with AvroSchemaProvider {

  import CfAvroSchemaRegistry._
  val config = system.settings.config
  val client = new ConfluentSchemaRegistryClient(Uri(config.getString(CONFIG_CF_REGISTRY_URL_BASE)))

  override private[schema] def getSchema(id: Int): Option[Schema] = try {
    Some(client.getSchema(id))
  } catch {
    case e: Throwable => e.printStackTrace(); None
  }

  override private[schema] def registerSchema(cls: Class[_], schema: Schema): Int = {
    val subject = cls.getName
    client.registerSchema(subject, schema)
  }

  override private[schema] def getVersions(cls: Class[_]): List[(Int, Schema)] = {
    val subject = cls.getName
    client.getVersions(subject).toList.map { version =>
      client.getSchema(subject, version)
    }
  }

  class ConfluentSchemaRegistryClient(baseUrl: Uri) {
    implicit val system = CfAvroSchemaRegistry.this.system

    implicit val materializer = ActorMaterializer.create(system)

    private val mapper = new ObjectMapper

    def getSubjects: Iterator[String] = {
      val j = mapper.readValue(get("/subjects"), classOf[JsonNode])
      if (!j.has("error_code")) {
        j.elements().asScala.map(_.textValue())
      } else {
        if (j.get("error_code").intValue() == 40401) {
          Iterator.empty
        } else {
          throw new RuntimeException(j.get("message").textValue())
        }
      }
    }

    def getVersions(subject: String): Iterator[Int] = {
      val j = mapper.readValue(get(s"/subjects/$subject/versions"), classOf[JsonNode])
      if (!j.has("error_code")) {
        j.elements().asScala.map(_.intValue())
      } else {
        if (j.get("error_code").intValue() == 40401) {
          Iterator.empty
        } else {
          throw new RuntimeException(j.get("message").textValue())
        }
      }
    }

    def getSchema(subject: String, version: Int): (Int, Schema) = {
      val j = mapper.readValue(get(s"/subjects/$subject/versions/$version"), classOf[JsonNode])
      if (j.has("error_code")) throw new RuntimeException(j.get("message").textValue())
      (j.get("id").intValue(), new Schema.Parser().parse(j.get("schema").textValue()))
    }

    def getSchema(id: Int) = {
      val j = mapper.readValue(get(s"/schemas/ids/$id"), classOf[JsonNode])
      if (j.has("error_code")) throw new RuntimeException(j.get("message").textValue())
      new Schema.Parser().parse(j.get("schema").textValue())
    }

    def checkSchema(subject: String, schema: Schema): Option[Int] = {
      val entity = mapper.createObjectNode()
      entity.put("schema", schema.toString)
      val j = mapper.readValue(post(s"/subjects/$subject", entity.toString), classOf[JsonNode])
      if (j.has("error_code")) throw new RuntimeException(j.get("message").textValue())
      if (j.has("id")) Some(j.get("id").intValue()) else None
    }

    def registerSchema(subject: String, schema: Schema): Int = {
      val entity = mapper.createObjectNode()
      entity.put("schema", schema.toString)
      val j = mapper.readValue(post(s"/subjects/$subject/versions", entity.toString), classOf[JsonNode])
      if (j.has("error_code")) throw new RuntimeException(j.get("message").textValue())
      if (j.has("id")) j.get("id").intValue() else throw new IllegalArgumentException
    }

    private def get(path: String): String = http(path) { connection =>
      connection.setRequestMethod("GET")
    }

    private def post(path: String, entity: String): String = http(path) { connection =>
      connection.addRequestProperty("Content-Type", "application/json")
      connection.addRequestProperty("Accept", "application/vnd.schemaregistry.v1+json, application/vnd.schemaregistry+json, application/json")
      connection.setDoOutput(true)
      connection.setRequestMethod("POST")
      val output = new DataOutputStream( connection.getOutputStream())
      output.write( entity.getBytes("UTF-8"))
    }

    private def http(path: String)(init: HttpURLConnection => Unit ): String = {
      val url = new URL(baseUrl.withPath(Uri.Path(path)).toString)
      val connection = url.openConnection.asInstanceOf[HttpURLConnection]
      connection.setConnectTimeout(5000)
      connection.setReadTimeout(5000)

      init(connection)

      val status = connection.getResponseCode()
      val inputStream = if (status == HttpURLConnection.HTTP_OK) {
        connection.getInputStream
      } else {
        connection.getErrorStream
      }
      val content = scala.io.Source.fromInputStream(inputStream).mkString
      if (inputStream != null) inputStream.close
      content
    }
  }

}
