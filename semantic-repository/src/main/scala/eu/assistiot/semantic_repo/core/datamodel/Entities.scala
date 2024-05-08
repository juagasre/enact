package eu.assistiot.semantic_repo.core.datamodel

final case class SuccessResponse(message: String, warnings: Option[Seq[String]] = None)

final case class ErrorResponse(error: String)

final case class InfoResponse(name: String, version: String)

final case class RootInfo(namespaces: Option[MongoSet[MongoModel.Namespace]])

final case class DocCompilationStartedInfo(message: String, handle: String, plugin: String)

final case class WebhookCreatedInfo(message: String, handle: String)
