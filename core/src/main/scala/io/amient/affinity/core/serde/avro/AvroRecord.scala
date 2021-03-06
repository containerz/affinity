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

package io.amient.affinity.core.serde.avro

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import com.fasterxml.jackson.annotation.JsonIgnore
import io.amient.affinity.core.serde.avro.schema.AvroSchemaProvider
import io.amient.affinity.core.util.ByteUtils
import org.apache.avro.Schema.Type._
import org.apache.avro.generic.GenericData.EnumSymbol
import org.apache.avro.generic._
import org.apache.avro.io.{BinaryDecoder, DecoderFactory, EncoderFactory}
import org.apache.avro.specific.SpecificRecord
import org.apache.avro.util.Utf8
import org.apache.avro.{AvroRuntimeException, Schema, SchemaBuilder}

import scala.collection.JavaConverters._
import scala.reflect.runtime.universe
import scala.reflect.runtime.universe._

object AvroRecord {

  private val MAGIC: Byte = 0

  private val typeSchemaCache = scala.collection.mutable.Map[Type, Schema]()

  def write(x: IndexedRecord, schemaId: Int): Array[Byte] = {
    write(x, x.getSchema, schemaId)
  }

  def write(x: Any, schema: Schema, schemaId: Int = -1): Array[Byte] = {
    if (x == null) {
      return null
    }
    val valueOut = new ByteArrayOutputStream()
    try {
      val encoder = EncoderFactory.get().binaryEncoder(valueOut, null)
      val writer = new GenericDatumWriter[Any](schema)
      if (schemaId >= 0) {
        valueOut.write(MAGIC)
        ByteUtils.writeIntValue(schemaId, valueOut)
      }
      writer.write(x, encoder)
      encoder.flush()
      valueOut.toByteArray
    } finally {
      valueOut.close
    }
  }

  def read[T: TypeTag](bytes: Array[Byte], cls: Class[T], schema: Schema): T = read(bytes, cls, schema, schema)

  def read[T: TypeTag](record: GenericContainer): T = {
    readDatum(record, typeOf[T], record.getSchema).asInstanceOf[T]
  }

  def read[T: TypeTag](bytes: Array[Byte], cls: Class[T], writerSchema: Schema, readerSchema: Schema): T = {
    val decoder = DecoderFactory.get().binaryDecoder(bytes, null)
    val reader = new GenericDatumReader[GenericRecord](writerSchema, readerSchema)
    val record: GenericRecord = reader.read(null, decoder)
    read(record)
  }


  /**
    *
    * @param bytes
    * @param schemaRegistry
    * @return AvroRecord for registered Type
    *         GenericRecord if no type is registered for the schema retrieved from the schemaRegistry
    *         null if bytes are null
    */
  def read(bytes: Array[Byte], schemaRegistry: AvroSchemaProvider): Any = {
    if (bytes == null) null
    else {
      val bytesIn = new ByteArrayInputStream(bytes)
      require(bytesIn.read() == MAGIC)
      val schemaId = ByteUtils.readIntValue(bytesIn)
      require(schemaId >= 0)
      val decoder: BinaryDecoder = DecoderFactory.get().binaryDecoder(bytesIn, null)
      schemaRegistry.schema(schemaId) match {
        case None => throw new IllegalArgumentException(s"Schema $schemaId doesn't exist")
        case Some((tpe, writerSchema)) =>
          if (tpe == null) {
            val reader = new GenericDatumReader[GenericRecord](writerSchema, writerSchema)
            reader.read(null, decoder)
          } else {
            val readerSchema = inferSchema(tpe)
            val reader = new GenericDatumReader[Any](writerSchema, readerSchema)
            val record = reader.read(null, decoder)
            readDatum(record, tpe, readerSchema)
          }
      }
    }
  }

  private def readDatum(datum: Any, tpe: Type, schema: Schema): Any = {
    if (datum == null) {
      return null
    }
    schema.getType match {
      case BOOLEAN => new java.lang.Boolean(datum.asInstanceOf[Boolean])
      case INT => new java.lang.Integer(datum.asInstanceOf[Int])
      case NULL => null
      case FLOAT => new java.lang.Float(datum.asInstanceOf[Float])
      case DOUBLE => new java.lang.Double(datum.asInstanceOf[Double])
      case LONG => new java.lang.Long(datum.asInstanceOf[Long])
      case BYTES => datum.asInstanceOf[java.nio.ByteBuffer]
      case STRING => String.valueOf(datum.asInstanceOf[Utf8])
      case RECORD =>
        val record = datum.asInstanceOf[GenericRecord]
        val constructor = tpe.decl(universe.termNames.CONSTRUCTOR).asMethod
        val params = constructor.paramLists(0)
        val arguments = record.getSchema.getFields.asScala.map { field =>
          readDatum(record.get(field.pos), params(field.pos).typeSignature, field.schema)
        }
        val classMirror = rootMirror.reflectClass(tpe.typeSymbol.asClass)
        val constructorMirror = classMirror.reflectConstructor(constructor)
        constructorMirror(arguments: _*)
      case ENUM => tpe match {
        case TypeRef(enumType, _, _) =>
          val moduleMirror = rootMirror.reflectModule(enumType.termSymbol.asModule)
          val instanceMirror = rootMirror.reflect(moduleMirror.instance)
          val methodMirror = instanceMirror.reflectMethod(enumType.member(TermName("withName")).asMethod)
          methodMirror(datum.asInstanceOf[EnumSymbol].toString)
      }
      case MAP => datum.asInstanceOf[java.util.Map[Utf8, _]].asScala.toMap
        .map { case (k, v) => (
          k.toString,
          readDatum(v, tpe.typeArgs(1), schema.getValueType))
        }
      case ARRAY => val iterable = datum.asInstanceOf[java.util.Collection[_]].asScala
        .map(item => readDatum(item, tpe.typeArgs(0), schema.getElementType))
        if (tpe <:< typeOf[Set[_]]) {
          iterable.toSet
        } else if (tpe <:< typeOf[List[_]]) {
          iterable.toList
        } else if (tpe <:< typeOf[Vector[_]]) {
          iterable.toVector
        } else if (tpe <:< typeOf[IndexedSeq[_]]) {
          iterable.toIndexedSeq
        } else if (tpe <:< typeOf[Seq[_]]) {
          iterable.toSeq
        } else {
          iterable
        }
      case FIXED => throw new NotImplementedError("Avro Fixed are not supported")
      case UNION => throw new NotImplementedError("Avro Unions are not supported")
    }
  }

  def inferSchema[X: TypeTag, AnyRef <: X](cls: Class[X]): Schema = inferSchema(typeOf[X])

  private def inferSchema(tpe: Type): Schema = {

    typeSchemaCache.get(tpe) match {
      case Some(schema) => schema
      case None =>

        val schema: Schema =
          if (tpe =:= typeOf[String]) {
            SchemaBuilder.builder().stringType()
          } else if (tpe =:= definitions.IntTpe) {
            SchemaBuilder.builder().intType()
          } else if (tpe =:= definitions.LongTpe) {
            SchemaBuilder.builder().longType()
          } else if (tpe =:= definitions.BooleanTpe) {
            SchemaBuilder.builder().booleanType()
          } else if (tpe =:= definitions.FloatTpe) {
            SchemaBuilder.builder().floatType()
          } else if (tpe =:= definitions.DoubleTpe) {
            SchemaBuilder.builder().doubleType()
          } else if (tpe =:= typeOf[java.nio.ByteBuffer]) {
            SchemaBuilder.builder().bytesType()
          } else if (tpe =:= typeOf[String]) {
            SchemaBuilder.builder().stringType()
          } else if (tpe =:= typeOf[Null]) {
            SchemaBuilder.builder().nullType()
          } else if (tpe <:< typeOf[Map[_, _]]) {
            SchemaBuilder.builder().map().values().`type`(inferSchema(tpe.typeArgs(1)))
          } else if (tpe <:< typeOf[Iterable[_]]) {
            SchemaBuilder.builder().array().items().`type`(inferSchema(tpe.typeArgs(0)))
          } else if (tpe <:< typeOf[scala.Enumeration#Value]) {
            tpe match {
              case TypeRef(enumType, _, _) =>
                val moduleMirror = rootMirror.reflectModule(enumType.termSymbol.asModule)
                val instanceMirror = rootMirror.reflect(moduleMirror.instance)
                val methodMirror = instanceMirror.reflectMethod(enumType.member(TermName("values")).asMethod)
                val enumSymbols = methodMirror().asInstanceOf[Enumeration#ValueSet]
                val args = enumSymbols.toSeq.map(_.toString)
                SchemaBuilder.builder().enumeration(enumType.toString.dropRight(5)).symbols(args: _*)
            }
          } else if (tpe <:< typeOf[AvroRecord[_]]) {

            val moduleMirror = rootMirror.reflectModule(tpe.typeSymbol.companion.asModule)
            val companionMirror = rootMirror.reflect(moduleMirror.instance)
            val constructor = tpe.decl(universe.termNames.CONSTRUCTOR)
            val params = constructor.asMethod.paramLists(0)
            val assembler = params.zipWithIndex.foldLeft(SchemaBuilder.record(tpe.toString).fields()) {
              case (assembler, (symbol, i)) =>
                val field = assembler.name(symbol.name.toString).`type`(inferSchema(symbol.typeSignature))
                val defaultDef = companionMirror.symbol.typeSignature.member(TermName(s"apply$$default$$${i + 1}"))
                if (defaultDef == NoSymbol) {
                  field.noDefault()
                } else {
                  val methodMirror = companionMirror.reflectMethod(defaultDef.asMethod)
                  val default = methodMirror()
                  if (symbol.typeSignature <:< typeOf[scala.Enumeration#Value]) {
                    field.withDefault(default.asInstanceOf[Enumeration#Value].toString)
                  } else if (tpe <:< typeOf[Map[_, _]]) {
                    field.withDefault(default.asInstanceOf[Map[String, _]].asJava)
                  } else if (symbol.typeSignature <:< typeOf[Iterable[_]]) {
                    field.withDefault(default.asInstanceOf[Iterable[_]].toList.asJava)
                  } else {
                    field.withDefault(default)
                  }
                }
            }
            assembler.endRecord()
          } else {
            throw new IllegalArgumentException("Unsupported Avro Case Class type " + tpe.toString)
          }

        typeSchemaCache.put(tpe, schema)
        schema
    }
  }
}

abstract class AvroRecord[X: TypeTag] extends SpecificRecord with java.io.Serializable {

  @JsonIgnore val schema: Schema = AvroRecord.inferSchema(typeOf[X])
  private val schemaFields = schema.getFields
  private val params = getClass.getConstructors()(0).getParameters
  require(params.length == schemaFields.size,
    s"number of constructor arguments (${params.length}) is not equal to schema field count (${schemaFields.size})")

  @transient private val declaredFields = getClass.getDeclaredFields
  @transient private val fields = params.zipWithIndex.map { case (param, pos) => {
    val field = declaredFields(pos)
    require(param.getType == field.getType,
      s"field `${field.getType}` at position $pos doesn't match expected `$param`")
    field.setAccessible(true)
    pos -> field
  }
  }.toMap

  override def getSchema: Schema = schema

  final override def get(i: Int): AnyRef = {
    val schemaField = schema.getFields.get(i)
    val field = fields(i)
    schemaField.schema().getType match {
      case ARRAY => field.get(this).asInstanceOf[Iterable[_]].asJava
      case ENUM => new EnumSymbol(schemaField.schema, field.get(this))
      case MAP => field.get(this).asInstanceOf[Map[String, _]].asJava
      case _ => field.get(this)
    }
  }

  final override def put(i: Int, v: scala.Any): Unit = {
    throw new AvroRuntimeException("Scala AvroRecord is immutable")
  }
}
