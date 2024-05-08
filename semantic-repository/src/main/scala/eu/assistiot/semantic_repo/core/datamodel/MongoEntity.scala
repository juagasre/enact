package eu.assistiot.semantic_repo.core.datamodel

import org.bson.{BsonReader, BsonWriter}
import org.bson.codecs.{Codec, DecoderContext, EncoderContext}
import org.mongodb.scala.bson.collection.Document
import org.mongodb.scala.bson.codecs.ImmutableDocumentCodec

import scala.reflect.{ClassTag, classTag}

/**
 * Base trait for Mongo-backed entity companion objects.
 *
 * Should become redundant when the MongoDB driver upgrades to Scala 3.
 *
 * @tparam T case class of the entity
 */
trait MongoEntity[T : ClassTag]:
  def apply(doc: Document): T

  def unapply(entity: T): Document

  def codecProvider(implicit docCodec: ImmutableDocumentCodec) = new Codec[T]:
    override def decode(reader: BsonReader, decoderContext: DecoderContext): T =
      apply(docCodec.decode(reader, decoderContext))

    override def encode(writer: BsonWriter, value: T, encoderContext: EncoderContext) =
      docCodec.encode(writer, unapply(value), encoderContext)

    override def getEncoderClass: Class[T] = classTag[T].runtimeClass.asInstanceOf[Class[T]]
