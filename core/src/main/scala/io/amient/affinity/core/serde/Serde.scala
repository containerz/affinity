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

package io.amient.affinity.core.serde


import akka.serialization.JSerializer

trait Serde extends JSerializer  {

  def fromBytes(bytes: Array[Byte]): Any

  def toBytes(obj: Any): Array[Byte]

  //////////////////////////////////////////////////////////////////////////

  override def includeManifest: Boolean = false

  override def toBinary(obj: AnyRef): Array[Byte] = toBytes(obj)

  override protected def fromBinaryJava(bytes: Array[Byte], manifest: Class[_]): AnyRef = fromBytes(bytes) match {
    case null => null
    case ref: AnyRef => ref
    case u: Unit => u.asInstanceOf[AnyRef]
    case z: Boolean => z.asInstanceOf[AnyRef]
    case b: Byte => b.asInstanceOf[AnyRef]
    case c: Char => c.asInstanceOf[AnyRef]
    case s: Short => s.asInstanceOf[AnyRef]
    case i: Int => i.asInstanceOf[AnyRef]
    case l: Long => l.asInstanceOf[AnyRef]
    case f: Float => f.asInstanceOf[AnyRef]
    case d: Double => d.asInstanceOf[AnyRef]
  }

}
