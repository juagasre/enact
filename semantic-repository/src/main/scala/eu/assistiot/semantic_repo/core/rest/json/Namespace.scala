package eu.assistiot.semantic_repo.core.rest.json

import eu.assistiot.semantic_repo.core.datamodel.{MongoModel, MongoSet}
import eu.assistiot.semantic_repo.core.rest.AbstractNamespaceContext
import eu.assistiot.semantic_repo.core.rest.json.update.{NamespaceUpdateModel, UpdateMode}
import spray.json.DefaultJsonProtocol.*
import spray.json.RootJsonFormat

/**
 * Namespace client model.
 * @param namespace name of the namespace
 */
case class NamespaceClientModel(namespace: String, models: Option[SetClientModel[ModelClientModel]],
                                metadata: Option[MetadataClientModel])
  extends HasMetadata, HasName, HasSet[ModelClientModel]:
  override def getName = namespace
  override def getSet = models

/**
 * This trait should be extended by JsonSupport.
 */
trait NamespaceSupport extends ModelSupport, SetSupport:
  /**
   * Type alias for Mongo model representing a namespace with models.
   */
  type mongoNsMs = (MongoModel.Namespace, Option[MongoSet[MongoModel.Model]])

  /**
   * Abstract client format <-> JSON client representation.
   * This is needed for cases where we don't want to apply the Mongo DM <-> client DM transformation.
   *
   * Used for example in integration tests.
   */
  implicit val namespaceClientFormat: RootJsonFormat[NamespaceClientModel] =
    jsonFormat3(NamespaceClientModel.apply)
  implicit val namespaceUpdateClientFormat: RootJsonFormat[NamespaceUpdateModel] =
    jsonFormat1(NamespaceUpdateModel.apply)

  /**
   * Translation of MongoModel.Namespace to NamespaceClientModel.
   */
  implicit val namespaceFormat: TransformingJsonWriter[MongoModel.Namespace, NamespaceClientModel] =
    new TransformingJsonWriter:
      override val internalFormat = namespaceClientFormat
      override def transformTo(obj: MongoModel.Namespace) =
        NamespaceClientModel(
          obj.name,
          None,
          metadataFormat.transformTo(obj.metadata),
        )

  /**
   * Translation of (Namespace, MongoSet[Model]) tuples.
   */
  implicit val namespaceWithSetFormat: TransformingJsonWriter[mongoNsMs, NamespaceClientModel] =
    new TransformingJsonWriter:
      override val internalFormat = namespaceClientFormat
      override def transformTo(obj: mongoNsMs) =
        namespaceFormat.transformTo(obj._1).copy(models = obj._2 map setTransformingWriter.transformTo)

  implicit def namespaceInsertFormat(implicit nc: AbstractNamespaceContext):
  ValidatingTransformingJsonReader[MongoModel.Namespace, NamespaceUpdateModel] =
    implicit val um: UpdateMode = UpdateMode.Insert
    new ValidatingTransformingJsonReader:
      val internalFormat = namespaceUpdateClientFormat
      def transformFrom(obj: NamespaceUpdateModel) =
        MongoModel.Namespace(nc.ns,
          obj.metadata.map(metadataInsertFormat.transformFrom) getOrElse MongoModel.Metadata())