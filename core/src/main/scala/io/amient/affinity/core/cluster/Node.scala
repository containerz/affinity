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

package io.amient.affinity.core.cluster


import akka.actor.{ActorSystem, Props}
import akka.util.Timeout
import com.typesafe.config.Config
import io.amient.affinity.core.ack._
import io.amient.affinity.core.actor.Controller._
import io.amient.affinity.core.actor._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.reflect.ClassTag

object Node {
  final val CONFIG_AKKA_STARTUP_TIMEOUT_MS = "affinity.node.startup.timeout.ms"
  final val CONFIG_AKKA_SHUTDOWN_TIMEOUT_MS = "affinity.node.shutdown.timeout.ms"
  final val CONFIG_AKKA_SYSTEM_NAME = "affinity.node.name"
  final val CONFIG_AKKA_HOST = "akka.remote.netty.tcp.hostname"
  final val CONFIG_AKKA_PORT = "akka.remote.netty.tcp.port"

  final val CONFIG_PARTITION_LIST = "affinity.node.region.partitions"
}

class Node(config: Config) {

  import Node._

  private val actorSystemName = config.getString(CONFIG_AKKA_SYSTEM_NAME)
  private val startupTimeout = config.getInt(CONFIG_AKKA_SHUTDOWN_TIMEOUT_MS) milliseconds
  private val shutdownTimeout = config.getInt(CONFIG_AKKA_SHUTDOWN_TIMEOUT_MS) milliseconds

  implicit val system = ActorSystem.create(actorSystemName, config)

  private val controller = system.actorOf(Props(new Controller), name = "controller")

  //in case the process is stopped from outside
  sys.addShutdownHook {
    controller ! GracefulShutdown()
    //we cannot use the future returned by system.terminate() above because shutdown may have already been invoked
    Await.ready(system.whenTerminated, shutdownTimeout)
  }

  import system.dispatcher
  implicit val scheduler = system.scheduler

  final def shutdown(): Unit = {
    controller ! GracefulShutdown()
    Await.ready(system.whenTerminated, shutdownTimeout)
  }

  /**
    * @param creator
    * @param tag
    * @tparam T
    * @return Future holding the httpPort on which the gateway listens
    */
  def startGateway[T <: Gateway](creator: => T)(implicit tag: ClassTag[T]): Future[Int] = {
    implicit val timeout = Timeout(startupTimeout)
    ack[Int](controller, CreateGateway(Props(creator)))
  }

  def startRegion[T <: Partition](partitionCreator: => T)(implicit tag: ClassTag[T]): Future[Unit] = {
    implicit val timeout = Timeout(startupTimeout)
    ack[Unit](controller, CreateRegion(Props(partitionCreator)))
  }

  def startServices(services: Props*): Future[Unit] = {
    require(services.forall(props => classOf[Service].isAssignableFrom(props.actorClass)))
    implicit val timeout = Timeout(startupTimeout)
    ack(controller, CreateServiceContainer(services))
  }

  implicit def serviceCreatorToProps[T <: Service](creator: => T)(implicit tag: ClassTag[T]): Props = {
    Props(creator)
  }
}
