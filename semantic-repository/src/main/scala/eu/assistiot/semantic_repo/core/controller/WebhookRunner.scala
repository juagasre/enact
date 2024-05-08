package eu.assistiot.semantic_repo.core.controller

import akka.actor.typed.{ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.headers.RawHeader
import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import eu.assistiot.semantic_repo.core.rest.ObjectPathContext
import eu.assistiot.semantic_repo.core.rest.json.{WebhookContextClientModel, WebhookSupport}
import spray.json.*

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

object WebhookRunner:
  sealed trait Command

  /**
   * Command: call a webhook.
   * @param hook the webhook
   * @param timestamp timestamp of the action that occurred
   * @param context object path context of what happened
   * @param body body of the webhook describing what exactly happened, passed to the client.
   *                Use a JsonWriter to create the context.
   */
  case class CallWebhook(hook: MongoModel.Webhook, timestamp: DateTime, context: ObjectPathContext, body: JsValue)
    extends Command

/**
 * Executes the webhooks by making HTTP calls.
 * @param sendRequest service that actually performs the requests
 */
class WebhookRunner(sendRequest: HttpRequest => Future[HttpResponse]) extends WebhookSupport:
  import WebhookRunner.*

  def apply(): Behavior[Command] = Behaviors.receive { (ctx, m) =>
    implicit val sys: ActorSystem[Nothing] = ctx.system
    implicit val ec: ExecutionContext = ctx.executionContext

    m match
      case CallWebhook(hook, timestamp, context, body) =>
        val entity = JsObject(
          "action" -> JsString(hook.action.key),
          "hookId" -> JsString(hook._id.toString),
          "timestamp" -> JsString(timestamp.toIsoDateTimeString()),
          "context" -> WebhookContextClientModel(context).toJson,
          "body" -> body,
        )
        // TODO: set headers
        // TODO: use a connection pool
        //   https://doc.akka.io/docs/akka-http/10.4.0/client-side/request-level.html#flow-based-variant
        //   Watch out for thread starvation...
        // TODO: move this to a separate actor system
        val request = HttpRequest(
          method = HttpMethods.POST,
          uri = hook.callback,
          entity = HttpEntity(ContentTypes.`application/json`, entity.compactPrint),
        )

        try {
          // TODO: use configurable timeouts
          val response = Await.result(sendRequest(request), 5.seconds)
          response.entity.discardBytes()
          if !response.status.isSuccess() then
            ctx.log.warn(s"Non-successful response ${response.status} on webhook at ${hook.callback}")
        } catch {
          case ex: Throwable => ctx.log.warn(s"Failed to execute a webhook at ${hook.callback}", ex)
        }

        Behaviors.same
  }
