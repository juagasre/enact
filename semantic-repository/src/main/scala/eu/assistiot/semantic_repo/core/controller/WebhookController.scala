package eu.assistiot.semantic_repo.core.controller

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.DateTime
import com.mongodb.client.result.{DeleteResult, InsertOneResult}
import eu.assistiot.semantic_repo.core.*
import eu.assistiot.semantic_repo.core.datamodel.*
import eu.assistiot.semantic_repo.core.datamodel.search.SearchSupport
import eu.assistiot.semantic_repo.core.rest.ObjectPathContext
import org.bson.types.ObjectId
import org.mongodb.scala.*
import org.mongodb.scala.model.Filters as f
import spray.json.JsonWriter

import scala.concurrent.{ExecutionContextExecutor, Future}

/**
 * Controller for managing and running the webhooks.
 * @param sys actor system
 */
class WebhookController(sys: ActorSystem[Guardian.Command]) extends ActorSystemUser, SearchSupport:
  implicit val system: ActorSystem[Guardian.Command] = sys
  implicit val executionContext: ExecutionContextExecutor = system.executionContext

  def getWebhookSet(params: MongoSetParamsValidated[MongoModel.Webhook]): Future[MongoSet[MongoModel.Webhook]] =
    MongoModel.webhookCollection.findToSet(params, f.empty()).toFuture

  def getWebhook(hookId: ObjectId): Future[Option[MongoModel.Webhook]] =
    MongoModel.webhookCollection.find(
      f.eq("_id", hookId)
    ).first.toFutureOption
    
  def insertWebhook(hook: MongoModel.Webhook): Future[InsertOneResult] =
    MongoModel.webhookCollection.insertOne(hook).toFuture

  def deleteWebhook(hookId: ObjectId): Future[DeleteResult] =
    MongoModel.webhookCollection.deleteOne(
      f.eq("_id", hookId)
    ).toFuture

  def dispatchWebhook[T : JsonWriter]
  (action: MongoModel.WebhookAction, pathContext: ObjectPathContext, timestamp: DateTime, body: T): Unit =
    sys ! Guardian.WebhookCommand(
      WebhookDispatcher.DispatchHook(
        action,
        pathContext,
        timestamp,
        implicitly[JsonWriter[T]].write(body)
      )
    )
