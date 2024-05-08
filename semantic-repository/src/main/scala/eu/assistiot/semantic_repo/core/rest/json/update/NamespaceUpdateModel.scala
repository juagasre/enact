package eu.assistiot.semantic_repo.core.rest.json.update

import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import eu.assistiot.semantic_repo.core.rest.resources.ModelVersionResource
import org.bson.conversions.Bson

/**
 * Model for update operations of a Semantic Repository namespace.
 *
 * @param metadata metadata
 */
case class NamespaceUpdateModel(metadata: Option[MetadataUpdateModel] = None)
  extends UpdateModel[MongoModel.Namespace]:

  override def validateInner(implicit updateMode: UpdateMode): Unit =
    metadata.foreach(_.validateInner)

  override protected def validatePreUpdateInner(entity: MongoModel.Namespace): Unit =
    metadata.foreach(_.validatePreUpdateInner(entity.metadata))

  override def getMongoUpdates: Seq[Option[Bson]] =
    metadata.map(_.getMongoUpdates).getOrElse(Seq())
