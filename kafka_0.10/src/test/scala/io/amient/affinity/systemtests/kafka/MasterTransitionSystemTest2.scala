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

package io.amient.affinity.systemtests.kafka

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpResponse, Uri, headers}
import akka.util.Timeout
import io.amient.affinity.core.ack._
import io.amient.affinity.core.actor.Gateway
import io.amient.affinity.core.http.Encoder
import io.amient.affinity.core.http.RequestMatchers.{HTTP, PATH}
import io.amient.affinity.testutil.MyTestPartition.{GetValue, PutValue}
import io.amient.affinity.testutil.SystemTestBaseWithKafka
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Random


class MasterTransitionSystemTest2 extends FlatSpec with SystemTestBaseWithKafka with Matchers {

  val config = configure("systemtests")

  val gateway = new TestGatewayNode(config, new Gateway {

    import context.dispatcher
    implicit val scheduler = context.system.scheduler

    override def handle: Receive = {
      case HTTP(GET, PATH(key), _, response) =>
        implicit val timeout = Timeout(500 milliseconds)
        delegateAndHandleErrors(response, ack(cluster, GetValue(key))) {
          case value => Encoder.json(OK, value, gzip = false)
        }

      case HTTP(POST, PATH(key, value), _, response) =>
        implicit val timeout = Timeout(1500 milliseconds)
        delegateAndHandleErrors(response, ack(cluster, PutValue(key, value))) {
          case result => HttpResponse(SeeOther, headers = List(headers.Location(Uri(s"/$key"))))
        }
    }
  })

  val region1 = new TestRegionNode(config, new MyTestPartition("consistency-test"))
  val region2 = new TestRegionNode(config, new MyTestPartition("consistency-test"))

  override def afterAll(): Unit = {
    try {
      gateway.shutdown()
      region1.shutdown()
      region2.shutdown()
    } finally {
      super.afterAll()
    }
  }

  "Master Transition" should "not lead to inconsistent state" in {
    val requestCount = new AtomicLong(0L)
    val errorCount = new AtomicLong(0L)
    val stopSignal = new AtomicBoolean(false)
    val expected = scala.collection.mutable.Map[String, String]()


    import scala.concurrent.ExecutionContext.Implicits.global

    val client = new Thread {

      override def run: Unit = {
        val random = new Random()
        val requests = scala.collection.mutable.ListBuffer[Future[String]]()
        while (!stopSignal.get) {
          Thread.sleep(2)
          if (isInterrupted) throw new InterruptedException
          val key = random.nextInt.toString
          val value = random.nextInt.toString
          requests += gateway.http(POST, gateway.uri(s"/$key/$value")) map {
            case response =>
              expected += key -> value
              response.status.value
          } recover {
            case e: Throwable => e.getMessage
          }
        }
        requestCount.set(requests.size)
        try {
          val statuses = Await.result(Future.sequence(requests), 5 seconds).groupBy(x => x).map {
            case (status, list) => (status, list.length)
          }
          errorCount.set(requestCount.get - statuses("303 See Other"))
        } catch {
          case e: Throwable =>
            e.printStackTrace()
            errorCount.set(requests.size)
        }

      }
    }
    client.start
    try {
      Thread.sleep(100)
      region1.shutdown()
      stopSignal.set(true)
      client.join()
      Thread.sleep(100)
      errorCount.get should be(0L)
      val x = Await.result(Future.sequence(expected.map { case (key, value) =>
        gateway.http(GET, gateway.uri(s"/$key")).map {
          response => (response.entity, jsonStringEntity(value))
        }
      }), 10 seconds)
      x.count { case (entity, expected) => entity != expected } should be(0)
    } finally {
      client.interrupt()
    }

  }

}
