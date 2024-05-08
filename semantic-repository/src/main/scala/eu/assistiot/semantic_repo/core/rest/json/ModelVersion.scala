package eu.assistiot.semantic_repo.core.rest.json

import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import eu.assistiot.semantic_repo.core.rest.AbstractModelVersionContext
import eu.assistiot.semantic_repo.core.rest.json.update.{ModelVersionUpdateModel, UpdateMode}
import spray.json.DefaultJsonProtocol.*
import spray.json.RootJsonFormat

/**
 * Client model of a specific version of a Semantic Repository model.
 *
 * @param version version tag
 * @param model name of the model
 * @param namespace name of the namespace this model is in
 * @param formats available formats for this model
 * @param defaultFormat default format to use
 * @param metadata metadata
 * @param documentation last documentation job
 */
case class ModelVersionClientModel(version: String, model: String, namespace: String,
                                   formats: Option[Map[String, StoredFileClientModel]], defaultFormat: Option[String],
                                   metadata: Option[MetadataClientModel],
                                   documentation: Option[DocumentationJobClientModel])
  extends HasMetadata, HasName:
  override def getName = version

trait ModelVersionSupport extends StoredFileSupport, MetadataSupport, DocumentationJobSupport:
  implicit val modelVersionClientFormat: RootJsonFormat[ModelVersionClientModel] =
    jsonFormat7(ModelVersionClientModel.apply)

  implicit val modelVersionUpdateClientFormat: RootJsonFormat[ModelVersionUpdateModel] =
    jsonFormat2(ModelVersionUpdateModel.apply)

  implicit val modelVersionFormat: TransformingJsonWriter[MongoModel.ModelVersion, ModelVersionClientModel] =
    new TransformingJsonWriter:
      override val internalFormat = modelVersionClientFormat
      override def transformTo(obj: MongoModel.ModelVersion) =
        ModelVersionClientModel(
          obj.version,
          obj.modelName,
          obj.namespaceName,
          obj.formats.map( {
            _.map { (k, v) => (k, storedFileFormat.transformTo(v)) }
          } ),
          obj.defaultFormat,
          metadataFormat.transformTo(obj.metadata),
          obj.docJob.map(dj => docJobFormat.transformTo(dj)),
        )

  /**
   * Transforming update information + path context -> new model version.
   */
  implicit def modelVersionInsertFormat(implicit mvc: AbstractModelVersionContext):
  ValidatingTransformingJsonReader[MongoModel.ModelVersion, ModelVersionUpdateModel] =
    implicit val um: UpdateMode = UpdateMode.Insert
    new ValidatingTransformingJsonReader:
      val internalFormat = modelVersionUpdateClientFormat
      def transformFrom(obj: ModelVersionUpdateModel) =
        MongoModel.ModelVersion(
          mvc.version, mvc.model, mvc.ns, obj.defaultFormat,
          obj.metadata.map(metadataInsertFormat.transformFrom) getOrElse MongoModel.Metadata()
        )
