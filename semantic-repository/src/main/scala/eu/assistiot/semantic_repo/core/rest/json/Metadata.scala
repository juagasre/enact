package eu.assistiot.semantic_repo.core.rest.json

import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import eu.assistiot.semantic_repo.core.rest.json.update.{MetadataUpdateModel, UpdateMode}
import spray.json.DefaultJsonProtocol.*
import spray.json.{JsValue, JsonFormat, RootJsonFormat}

/**
 * Type alias for a metadata map.
 */
type MetadataClientModel = Map[String, Either[String, Seq[String]]]

trait MetadataSupport:
  /**
   * MongoModel.Metadata -> wire format
   */
  implicit val metadataFormat: TransformingJsonWriter[MongoModel.Metadata, Option[MetadataClientModel]] =
    new TransformingJsonWriter:
      override def internalFormat = implicitly[JsonFormat[Option[MetadataClientModel]]]
      override def transformTo(obj: MongoModel.Metadata): Option[MetadataClientModel] = obj match
        case m if m.value.isEmpty => None
        case _ =>
          val valueMap: Map[String, Option[Either[String, Seq[String]]]] =
            // Keep non-empty arrays only, collapse single-element arrays into a scalar
            obj.value map { (k: String, v: Seq[String]) =>
              v match
                case _ if v.length > 1 => (k, Some(Right(v)))
                case Seq(el) => (k, Some(Left(el)))
                case _ => (k, None)
            }
          Some(for ((k, Some(v)) <- valueMap) yield k -> v)

  /**
   * wire format <-> MetadataUpdateModel (intermediate step)
   */
  implicit val metadataUpdateClientFormat: TransformingJsonFormat[MetadataUpdateModel, MetadataClientModel] =
    new TransformingJsonFormat:
      override def internalFormat = implicitly[RootJsonFormat[MetadataClientModel]]
      override def transformFrom(obj: MetadataClientModel) = MetadataUpdateModel(obj)
      override def transformTo(obj: MetadataUpdateModel) = obj.value

  /**
   * MetadataUpdateModel -> MongoModel.Metadata that can be inserted into Mongo
   */
  implicit val metadataInsertFormat: TransformingJsonReader[MongoModel.Metadata, MetadataUpdateModel] =
    new TransformingJsonReader:
      override def internalFormat = metadataUpdateClientFormat
      override def transformFrom(obj: MetadataUpdateModel): MongoModel.Metadata =
        MongoModel.Metadata(
          obj.value map { (k, v) => v match
            case Right(seq) => (k, seq)
            case Left(el) => (k, Seq(el))
          }
        )
