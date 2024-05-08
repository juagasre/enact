package eu.assistiot.semantic_repo.core.rest.resources

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import eu.assistiot.semantic_repo.core.controller.WebhookController
import eu.assistiot.semantic_repo.core.datamodel.{ErrorResponse, MongoModel, SuccessResponse, WebhookCreatedInfo}
import eu.assistiot.semantic_repo.core.rest.Directives.*
import eu.assistiot.semantic_repo.core.rest.json.*
import fr.davit.akka.http.metrics.core.scaladsl.server.HttpMetricsDirectives.*
import org.bson.types.ObjectId

class WebhookResource(webhookContr: WebhookController) extends MongoResource:
  val route: Route = handleExceptions(mongoExceptionHandler) {
    pathPrefixLabeled("webhook") {
      pathEndOrSingleSlash {
        getWebhooksRoute ~
        handleRejections(postRejectionHandler("Unknown error")) {
          postWebhookRoute
        }
      } ~
      pathPrefixLabeled(ObjectIdMatcher, ":id") { hookId =>
        pathEndOrSingleSlash {
          getWebhookRoute(hookId) ~ deleteWebhookRoute(hookId)
        }
      }
    }
  }

  def getWebhooksRoute: Route = get {
    val paramsDir = extractMongoSetParams[MongoModel.Webhook]
    paramsDir { params =>
      onSuccess(webhookContr.getWebhookSet(params)) { set =>
        complete(StatusCodes.OK, set)
      }
    }
  }

  def postWebhookRoute: Route = post {
    val entityDir = validatedEntity[MongoModel.Webhook]
    entityDir { webhook =>
      onSuccess(webhookContr.insertWebhook(webhook)) { _ =>
        complete(StatusCodes.OK, WebhookCreatedInfo(
          "Webhook created.", webhook._id.toString
        ))
      }
    }
  }

  def getWebhookRoute(hookId: ObjectId): Route = get {
    onSuccess(webhookContr.getWebhook(hookId)) {
      case None => complete(StatusCodes.NotFound,
        ErrorResponse(s"Webhook with ID '$hookId' could not be found."))
      case Some(hook) => complete(StatusCodes.OK, hook)
    }
  }

  def deleteWebhookRoute(hookId: ObjectId): Route = delete {
    requireForceParam {
      onSuccess(webhookContr.deleteWebhook(hookId)) {
        case dr if dr.getDeletedCount == 1 => complete(StatusCodes.OK,
          SuccessResponse(s"Deleted webhook with ID '$hookId'."))
        case _ => complete(StatusCodes.NotFound,
          ErrorResponse(s"Webhook with ID '$hookId' could not be found."))
      }
    }
  }
