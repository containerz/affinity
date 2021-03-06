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

package io.amient.affinity.core.http

import akka.http.scaladsl.model.{HttpMethod, HttpResponse}
import akka.http.scaladsl.model.Uri.{Path, Query}

import scala.annotation.tailrec
import scala.concurrent.Promise

object RequestMatchers {

  object HTTP {
    def unapply(exchange: HttpExchange): Option[(HttpMethod, Path, Query, Promise[HttpResponse])] = {
      Some(exchange.request.method, exchange.request.uri.path, exchange.request.uri.query(), exchange.promise)
    }
  }

  object PATH {
    def unapplySeq(path: Path): Option[Seq[String]] = {
      @tailrec
      def r(p: Path, acc: Seq[String] = Seq()): Seq[String] =
        if (p.isEmpty) acc
        else if (p.startsWithSlash) r(p.tail, acc)
        else if (p.tail.isEmpty) acc :+ p.head.toString
        else r(p.tail, acc :+ p.head.toString)

      Some(r(path))
    }
  }

  object INT {
    def unapply(any: Any): Option[Int] = {
      try {
        Some(Integer.parseInt(any.toString))
      } catch {
        case e: NumberFormatException => None
      }
    }
  }

  object QUERY {
    def unapplySeq(query: Query): Option[Seq[(String, String)]] = Some(query.sortBy(_._1))
  }
}
