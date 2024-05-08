package eu.assistiot.semantic_repo.core.controller

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.model.DateTime
import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import eu.assistiot.semantic_repo.core.rest.ObjectPathContext
import org.mongodb.scala.bson.BsonNull
import org.mongodb.scala.bson.conversions.Bson
import spray.json.JsValue
import org.mongodb.scala.*
import org.mongodb.scala.model.Filters as f

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

object WebhookDispatcher:
  sealed trait Command

  /**
   * Command: Find all applicable registered webhooks and dispatch them.
   * @param action action that occurred
   * @param pathContext object path context, can be RootContext
   * @param timestamp timestamp of the action that occurred
   * @param body body of the webhook describing what exactly happened, passed to the client.
   *                Use a JsonWriter to create the context.
   */
  final case class DispatchHook(action: MongoModel.WebhookAction, pathContext: ObjectPathContext, timestamp: DateTime,
                                body: JsValue) extends Command

/**
 * Webhook dispatcher actor – looks up registered webhooks for a given action and distributes them to runners.
 * @param runner reference to a webhook runner
 */
class WebhookDispatcher(runner: ActorRef[WebhookRunner.Command]):
  import WebhookDispatcher.*

  final def apply(): Behavior[Command] = Behaviors.receive { (ctx, m) =>
    m match
      case DispatchHook(action, pathContext, timestamp, body) =>
        // MongoDB query plan:
        // IXSCAN { action: 1, context_ns: 1, context_model: 1, context_version: 1 }
        val dispatchFuture = MongoModel.webhookCollection.find(f.and(
          f.eq("action", action.key),
          makePartFilter("context_ns", pathContext.getNamespace),
          makePartFilter("context_model", pathContext.getModel),
          makePartFilter("context_version", pathContext.getModelVersion),
        )).map { webhook =>
          runner ! WebhookRunner.CallWebhook(webhook, timestamp, pathContext, body)
        }.toFuture
        // TODO: fix this some day
        Await.result(dispatchFuture, 10.seconds)
        Behaviors.same
  }

  private def makePartFilter(fieldName: String, part: Option[String]): Bson = part match
    case None => f.eq(fieldName, BsonNull())
    case Some(value) => f.or(f.eq(fieldName, value), f.eq(fieldName, BsonNull()))
