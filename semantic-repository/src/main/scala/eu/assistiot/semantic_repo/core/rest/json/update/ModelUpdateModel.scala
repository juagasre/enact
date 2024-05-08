package eu.assistiot.semantic_repo.core.rest.json.update

import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import eu.assistiot.semantic_repo.core.rest.resources.ModelVersionResource
import org.bson.conversions.Bson

/**
 * Model for update operations of a Semantic Repository model.
 *
 * @param latestVersion pointer to the "latest" version tag of this model
 * @param metadata metadata
 */
case class ModelUpdateModel(latestVersion: Option[String] = None, metadata: Option[MetadataUpdateModel] = None)
  extends UpdateModel[MongoModel.Model]:

  override def validateInner(implicit updateMode: UpdateMode): Unit =
    validEmptyOrUnset(latestVersion, "latestVersion", { case Some(ModelVersionResource.verRegex()) => })
    metadata.foreach(_.validateInner)

  override protected def validatePreUpdateInner(entity: MongoModel.Model): Unit =
    metadata.foreach(_.validatePreUpdateInner(entity.metadata))

  override def getMongoUpdates: Seq[Option[Bson]] =
    Seq(
      propertySetUnset("latestVersion", latestVersion),
    ) ++ metadata.map(_.getMongoUpdates).getOrElse(Seq())
