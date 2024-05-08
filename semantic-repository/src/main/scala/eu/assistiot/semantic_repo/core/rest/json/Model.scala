package eu.assistiot.semantic_repo.core.rest.json

import eu.assistiot.semantic_repo.core.datamodel.{MongoModel, MongoSet}
import eu.assistiot.semantic_repo.core.rest.AbstractModelContext
import eu.assistiot.semantic_repo.core.rest.json.update.{ModelUpdateModel, UpdateMode}
import eu.assistiot.semantic_repo.core.rest.resources.ModelVersionResource
import spray.json.DefaultJsonProtocol.*
import spray.json.RootJsonFormat

import scala.util.{Failure, Success, Try}

/**
 * Client model of a Semantic Repository model.
 *
 * @param model name of the model
 * @param namespace name of the namespace this model is in
 * @param versions versions of the model
 * @param latestVersion pointer to the "latest" version tag of this model
 */
case class ModelClientModel(model: String, namespace: String, versions: Option[SetClientModel[ModelVersionClientModel]],
                            latestVersion: Option[String], metadata: Option[MetadataClientModel])
  extends HasMetadata, HasName, HasSet[ModelVersionClientModel]:
  override def getName = model
  override def getSet = versions

trait ModelSupport extends ModelVersionSupport, SetSupport:
  /**
   * Type alias for Mongo model representing a model with versions.
   */
  type mongoMoVs = (MongoModel.Model, Option[MongoSet[MongoModel.ModelVersion]])

  implicit val modelClientFormat: RootJsonFormat[ModelClientModel] = jsonFormat5(ModelClientModel.apply)
  implicit val modelUpdateClientFormat: RootJsonFormat[ModelUpdateModel] = jsonFormat2(ModelUpdateModel.apply)

  /**
   * Translation of MongoModel.Model to ModelClientModel.
   */
  implicit val modelFormat: TransformingJsonWriter[MongoModel.Model, ModelClientModel] =
    new TransformingJsonWriter:
      val internalFormat = modelClientFormat
      def transformTo(obj: MongoModel.Model) =
        ModelClientModel(
          obj.name,
          obj.namespaceName,
          None,
          obj.latestVersion,
          metadataFormat.transformTo(obj.metadata),
        )

  /**
   * Translation of (Model, MongoSet[ModelVersion]) tuples.
   */
  implicit val modelWithSetFormat: TransformingJsonWriter[mongoMoVs, ModelClientModel] =
    new TransformingJsonWriter:
      val internalFormat = modelClientFormat
      def transformTo(obj: mongoMoVs) =
        modelFormat.transformTo(obj._1).copy(versions = obj._2 map setTransformingWriter.transformTo)

  /**
   * Transforming update information + path context -> new model.
   */
  implicit def modelInsertFormat(implicit mc: AbstractModelContext):
  ValidatingTransformingJsonReader[MongoModel.Model, ModelUpdateModel] =
    implicit val um: UpdateMode = UpdateMode.Insert
    new ValidatingTransformingJsonReader:
      val internalFormat = modelUpdateClientFormat
      def transformFrom(obj: ModelUpdateModel) =
        MongoModel.Model(mc.model, mc.ns, obj.latestVersion,
          obj.metadata.map(metadataInsertFormat.transformFrom) getOrElse MongoModel.Metadata()
        )
