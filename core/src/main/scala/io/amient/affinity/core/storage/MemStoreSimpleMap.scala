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

package io.amient.affinity.core.storage

import scala.concurrent.Future
import scala.util.control.NonFatal

class MemStoreSimpleMap extends MemStore {

  private val internal = scala.collection.mutable.Map[MK, MV]()

  override def apply(key: MK): Future[Option[MV]] = try {
    Future.successful(internal.get(key))
  } catch {
    case NonFatal(e) => Future.failed(e)
  }

  override def iterator = internal.iterator

  override def size: Long = internal.size

  override protected[storage] def update(key: MK, value: MV): Option[MV] = internal.put(key, value)

  override protected[storage] def remove(key: MK):Option[MV] = internal.remove(key)
}
