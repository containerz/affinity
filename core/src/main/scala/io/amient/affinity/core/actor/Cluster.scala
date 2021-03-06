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

package io.amient.affinity.core.actor

import akka.actor.Actor
import akka.routing._
import io.amient.affinity.core.util.ObjectHashPartitioner

import scala.collection.mutable

object Cluster {
  final val CONFIG_NUM_PARTITIONS = "affinity.cluster.num.partitions"
}

class Cluster extends Actor {

  private val config = context.system.settings.config

  private val numPartitions = config.getInt(Cluster.CONFIG_NUM_PARTITIONS)

  private val routes = mutable.Map[Int, Routee]()

  //TODO #17 partitioner should be configurable via blackbox
  val partitioner = new ObjectHashPartitioner


  override def receive: Receive = {

    /**
      * relying on Region to assign partition name equal to physical partition id
      */

    case AddRoutee(routee: ActorRefRoutee) =>
      val partition = routee.ref.path.name.toInt
      routes.put(partition, routee)

    case RemoveRoutee(routee: ActorRefRoutee) =>
      val partition = routee.ref.path.name.toInt
      routes.remove(partition) foreach { removed =>
        if (removed != routee) routes.put(partition, removed)
      }

    case GetRoutees => sender ! Routees(routes.values.toIndexedSeq)

    case message => route(message)

  }

  private def route(message: Any): Unit = {
    val partition = message match {
      case (k, v) => partitioner.partition(k, numPartitions)
      case v => partitioner.partition(v, numPartitions)
    }

    routes.get(partition) match {
      case Some(routee) =>
        //println(s"routing $message to partition $partition represented by $routee")
        routee.send(message, sender)
      case None =>
        throw new IllegalStateException(s"Partition $partition is not represented in the cluster")

      /**
        * This means that no region has registered the partition which may happen for 2 reasons:
        * 1. all regions representing that partition are genuinely down and not coming back
        * 2. between a master failure and a standby takeover there may be a brief period
        * of the partition not being represented.
        *
        * Both of the cases will see IllegalStateException which have to be handled by ack-and-retry
        */

    }

  }


}
