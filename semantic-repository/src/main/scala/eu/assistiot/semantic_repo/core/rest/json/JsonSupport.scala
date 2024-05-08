package eu.assistiot.semantic_repo.core.rest.json

import eu.assistiot.semantic_repo.core.datamodel.*
import eu.assistiot.semantic_repo.core.rest.json.update.ValidationSupport
import spray.json.{DefaultJsonProtocol, JsValue, RootJsonFormat}

/**
 * Should extend all *Support traits.
 */
trait JsonSupport
  extends
    RootInfoSupport,
    ValidationSupport,
    DocumentationJobSupport,
    DocPluginSupport,
    WebhookSupport
  :

  import DefaultJsonProtocol.*

  implicit val errorFormat: RootJsonFormat[ErrorResponse] = jsonFormat1(ErrorResponse.apply)
  implicit val successFormat: RootJsonFormat[SuccessResponse] = jsonFormat2(SuccessResponse.apply)
  implicit val infoResponseFormat: RootJsonFormat[InfoResponse] = jsonFormat2(InfoResponse.apply)
  implicit val docCompilationInfoFormat: RootJsonFormat[DocCompilationStartedInfo] =
    jsonFormat3(DocCompilationStartedInfo.apply)
  implicit val webhookCreatedInfoFormat: RootJsonFormat[WebhookCreatedInfo] =
    jsonFormat2(WebhookCreatedInfo.apply)
