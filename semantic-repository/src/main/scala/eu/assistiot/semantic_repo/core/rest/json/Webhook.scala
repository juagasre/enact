package eu.assistiot.semantic_repo.core.rest.json

import eu.assistiot.semantic_repo.core.datamodel.{MongoModel, MongoSet}
import eu.assistiot.semantic_repo.core.rest.{ObjectPathContext, RootContext}
import eu.assistiot.semantic_repo.core.rest.json.update.*
import spray.json.DefaultJsonProtocol.*
import spray.json.RootJsonFormat

/**
 * Client model of a defined webhook.
 * @param id unique identifier
 * @param action action type
 * @param context object path context to which this hook should be applied
 * @param callback callback URL
 */
case class WebhookClientModel(id: String, action: String, context: WebhookContextClientModel, callback: String)

object WebhookContextClientModel:
  def apply(pathContext: ObjectPathContext): WebhookContextClientModel =
    WebhookContextClientModel(
      pathContext.getNamespace,
      pathContext.getModel,
      pathContext.getModelVersion,
    )

/**
 * A webhook's object path context.
 * @param namespace namespace
 * @param model model
 * @param version model version
 */
case class WebhookContextClientModel(namespace: Option[String], model: Option[String], version: Option[String])

case class WebhookListClientModel(webhooks: SetClientModel[WebhookClientModel])
  extends HasSet[WebhookClientModel]:
  override def getSet = Some(webhooks)

trait WebhookSupport extends SetSupport:
  implicit val webhookContextClientFormat: RootJsonFormat[WebhookContextClientModel] =
    jsonFormat3(WebhookContextClientModel.apply)
  implicit val webhookClientFormat: RootJsonFormat[WebhookClientModel] =
    jsonFormat4(WebhookClientModel.apply)
  implicit val webhookUpdateClientFormat: RootJsonFormat[WebhookUpdateModel] =
    jsonFormat3(WebhookUpdateModel.apply)
  implicit val webhookListClientFormat: RootJsonFormat[WebhookListClientModel] =
    jsonFormat1(WebhookListClientModel.apply)

  implicit val webhookFormat: TransformingJsonWriter[MongoModel.Webhook, WebhookClientModel] =
    new TransformingJsonWriter:
      override val internalFormat = webhookClientFormat
      override def transformTo(obj: MongoModel.Webhook) =
        WebhookClientModel(
          obj._id.toString,
          obj.action.key,
          WebhookContextClientModel(obj.context),
          obj.callback,
        )

  implicit val webhookInsertFormat: ValidatingTransformingJsonReader[MongoModel.Webhook, WebhookUpdateModel] =
    implicit val um: UpdateMode = UpdateMode.Insert
    new ValidatingTransformingJsonReader:
      override val internalFormat = webhookUpdateClientFormat
      override def transformFrom(obj: WebhookUpdateModel) =
        MongoModel.Webhook(
          MongoModel.Webhook.keyToAction(obj.action),
          obj.context.map { context =>
            ObjectPathContext.fromParts(context.namespace, context.model, context.version)
          } getOrElse RootContext,
          obj.callback,
        )

  implicit val webhookListFormat: TransformingJsonWriter[MongoSet[MongoModel.Webhook], WebhookListClientModel] =
    new TransformingJsonWriter:
      override val internalFormat = webhookListClientFormat
      override def transformTo(obj: MongoSet[MongoModel.Webhook]) = WebhookListClientModel(
        setTransformingWriter[MongoModel.Webhook, WebhookClientModel].transformTo(obj)
      )
