package eu.assistiot.semantic_repo.core.datamodel.search

import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import eu.assistiot.semantic_repo.core.rest.json.{MetadataClientModel, ModelClientModel}

/**
 * Trait providing [[SearchProvider]] implicits.
 * Should be implemented by HTTP resources. 
 */
trait SearchSupport:
  implicit val metadataSupport: SearchProvider[MongoModel.Metadata] = new SearchProvider[MongoModel.Metadata]:
    override def getSearchableFieldsInternal = Seq(SearchableField("", "", true))

  implicit val modelVersionSupport: RootSearchProvider[MongoModel.ModelVersion] =
    new RootSearchProvider[MongoModel.ModelVersion]:
      override def getSearchableFieldsInternal = Seq(
        SearchableField("version", "version"),
        SearchableField("defaultFormat", "defaultFormat"),
      ) ++ getChildFields[MongoModel.Metadata]("metadata", "metadata")

  implicit val modelSupport: RootSearchProvider[MongoModel.Model] = new RootSearchProvider[MongoModel.Model]:
    override def getSearchableFieldsInternal = Seq(
      SearchableField("model", "name"),
      SearchableField("latestVersion", "latestVersion"),
    ) ++ getChildFields[MongoModel.Metadata]("metadata", "metadata")

  implicit val namespaceSupport: RootSearchProvider[MongoModel.Namespace] =
    new RootSearchProvider[MongoModel.Namespace]:
      override def getSearchableFieldsInternal = Seq(
        SearchableField("namespace", "name"),
      ) ++ getChildFields[MongoModel.Metadata]("metadata", "metadata")

  implicit val webhookSupport: RootSearchProvider[MongoModel.Webhook] =
    new RootSearchProvider[MongoModel.Webhook]:
      override protected def getSearchableFieldsInternal = Seq(
        SearchableField("action", "action"),
        // TODO: search by context? what about index support?
      )
