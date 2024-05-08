package eu.assistiot.semantic_repo.core.rest.json.update

import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import eu.assistiot.semantic_repo.core.rest.resources.ContentResource
import org.bson.conversions.Bson

case class ModelVersionUpdateModel(defaultFormat: Option[String] = None, metadata: Option[MetadataUpdateModel] = None)
  extends UpdateModel[MongoModel.ModelVersion]:

  override def validateInner(implicit updateMode: UpdateMode): Unit =
    validEmptyOrUnset(defaultFormat, "defaultFormat", { case Some(ContentResource.formatRegex()) => })
    metadata.foreach(_.validateInner)

  override def validatePreUpdateInner(entity: MongoModel.ModelVersion): Unit =
    metadata.foreach(_.validatePreUpdateInner(entity.metadata))

  override def getMongoUpdates: Seq[Option[Bson]] =
    Seq(
      propertySetUnset("defaultFormat", defaultFormat),
    ) ++ metadata.map(_.getMongoUpdates).getOrElse(Seq())
