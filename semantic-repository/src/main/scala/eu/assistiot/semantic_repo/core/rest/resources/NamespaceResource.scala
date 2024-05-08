package eu.assistiot.semantic_repo.core.rest.resources

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.{DateTime, StatusCodes}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.{Rejection, RejectionHandler, Route}
import com.mongodb.client.result.DeleteResult
import eu.assistiot.semantic_repo.core.AppConfig
import eu.assistiot.semantic_repo.core.Exceptions.*
import eu.assistiot.semantic_repo.core.controller.WebhookController
import eu.assistiot.semantic_repo.core.datamodel.{ErrorResponse, MongoModel, SuccessResponse}
import eu.assistiot.semantic_repo.core.datamodel.*
import eu.assistiot.semantic_repo.core.rest.*
import eu.assistiot.semantic_repo.core.rest.Directives.*
import eu.assistiot.semantic_repo.core.rest.json.*
import eu.assistiot.semantic_repo.core.rest.json.update.NamespaceUpdateModel
import fr.davit.akka.http.metrics.core.scaladsl.server.HttpMetricsDirectives.*
import org.mongodb.scala.*
import org.mongodb.scala.model.Filters as f
import spray.json.DefaultJsonProtocol.*
import spray.json.JsObject

import scala.util.{Failure, Success, Try}
import scala.util.matching.*

object NamespaceResource:
  // Regex for matching valid namespace names
  final val nsRegexString = "^[a-zA-Z0-9][\\w-]{0,99}$"
  val nsRegex = nsRegexString.r

class NamespaceResource(webhookContr: WebhookController) extends MongoResource:
  import NamespaceResource.*

  private val innerRoute =
    getAllNamespacesRoute ~
    ignoreTrailingSlash {
      pathLabeled(nsRegex, ":ns") { ns =>
        implicit val nc: NamespaceContext = NamespaceContext(ns)
        getNamespaceRoute ~ patchNamespaceRoute ~ deleteNamespaceRoute
      }
    } ~
    // Translate POST rejections to HTTP 400
    handleRejections(postRejectionHandler("Invalid namespace name. " +
      "Namespace names must match regex: " + nsRegexString)) {
      post {
        ignoreTrailingSlash {
          pathLabeled(nsRegex, ":ns") { ns =>
            implicit val nc: NamespaceContext = NamespaceContext(ns)
            postNamespaceRoute
          }
        }
      }
    }

  /**
   * Outer route handling exceptions and filtering by segment count.
   */
  val route: Route = handleExceptions(mongoExceptionHandler) {
    pathPrefixTest(not("m" / Segment / Segment)) {
      pathPrefixLabeled("m") {
        innerRoute
      }
    }
  }

  /**
   * Get a list of namespaces.
   * @return
   */
  def getAllNamespacesRoute = pathEndOrSingleSlash {
    get {
      val paramsDir = extractMongoSetParams[MongoModel.Namespace]
      paramsDir { params =>
        val setFuture = MongoModel.namespaceCollection.findToSet(params).toFuture
        onSuccess(setFuture) { set =>
          complete(StatusCodes.OK, RootInfo(Some(set)))
        }
      }
    }
  }

  /**
   * Get a namespace by name.
   * @param nc namespace context
   * @return
   */
  def getNamespaceRoute(implicit nc: AbstractNamespaceContext) =
    get {
      val paramsDir = extractMongoSetParams[MongoModel.Model]
      paramsDir { params =>
        // TODO: only return the list of associated models when requested
        val nsFuture = MongoModel.namespaceCollection.find(namespaceFilter).first.toFuture
        val modelsFuture = MongoModel.modelCollection.findToSet(params, modelFilter).toFuture

        onSuccess(nsFuture) { _ match
          case null => complete(StatusCodes.NotFound, ErrorResponse(s"Namespace '$namespacePath' not found."))
          case ns =>
            onSuccess(modelsFuture) { modelSet =>
              complete(StatusCodes.OK, (ns, Some(modelSet)))
            }
        }
      }
    }

  /**
   * Post a new namespace.
   * @param nc namespace context
   * @return
   */
  def postNamespaceRoute(implicit nc: AbstractNamespaceContext) =
    post {
      val entityDirective = validatedEntityOrEmpty(MongoModel.Namespace(nc))
      entityDirective { entity =>
        val op = MongoModel.namespaceCollection.insertOne(entity).toFuture
        onSuccess(op) { _ =>
          complete(StatusCodes.OK, SuccessResponse(s"Created namespace '$namespacePath'."))
        }
      }
    }

  /**
   * Update an existing namespace.
   * @param nc namespace context
   * @return
   */
  def patchNamespaceRoute(implicit nc: AbstractNamespaceContext) =
    patchEntity[MongoModel.Namespace, NamespaceUpdateModel]
      (MongoModel.namespaceCollection, namespaceFilter, namespacePath, "namespace")

  /**
   * Delete a namespace.
   * @param nc namespace context
   * @return
   */
  def deleteNamespaceRoute(implicit nc: AbstractNamespaceContext) =
    // TODO: add a parameter to force a cascading delete (#38)
    delete {
      requireForceParam {
        val transactionFuture = MongoModel.transaction( { session =>
          MongoModel.modelCollection.find(session, modelFilter).collect.flatMap {
            case s if s.nonEmpty => throw NonEmptyEntityException("models")
            case _ =>
              MongoModel.namespaceCollection.deleteOne(session, namespaceFilter).collect.map {
                case Seq(result) if result.getDeletedCount == 1 => {}
                case _ => throw NotFoundException()
              }
          }.toSingle
        }, None ).toFuture

        onComplete(transactionFuture) {
          case Success(_) =>
            webhookContr.dispatchWebhook(
              MongoModel.WebhookAction.NamespaceDelete,
              nc,
              DateTime.now,
              JsObject()
            )
            complete(StatusCodes.OK, SuccessResponse(s"Deleted namespace '$namespacePath'."))
          case Failure(NonEmptyEntityException(what)) =>
            complete(StatusCodes.BadRequest,
              ErrorResponse(s"Namespace '$namespacePath' is not empty. Please delete the $what first."))
          case Failure(e: NotFoundException) =>
            complete(StatusCodes.NotFound, ErrorResponse(s"Namespace '$namespacePath' not found."))
          case Failure(e) => throw e
        }
      }
    }
