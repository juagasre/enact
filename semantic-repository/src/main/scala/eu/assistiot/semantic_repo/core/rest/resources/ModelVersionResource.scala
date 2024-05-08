package eu.assistiot.semantic_repo.core.rest.resources

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.{DateTime, StatusCodes}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.{Rejection, RejectionHandler, Route}
import com.mongodb.client.result.DeleteResult
import eu.assistiot.semantic_repo.core.Exceptions.*
import eu.assistiot.semantic_repo.core.controller.WebhookController
import eu.assistiot.semantic_repo.core.datamodel.{ErrorResponse, MongoModel, SuccessResponse}
import eu.assistiot.semantic_repo.core.datamodel.*
import eu.assistiot.semantic_repo.core.rest.*
import eu.assistiot.semantic_repo.core.rest.Directives.*
import eu.assistiot.semantic_repo.core.rest.json.*
import eu.assistiot.semantic_repo.core.rest.json.update.ModelVersionUpdateModel
import fr.davit.akka.http.metrics.core.scaladsl.server.HttpMetricsDirectives.*
import org.mongodb.scala.*
import org.mongodb.scala.model.Filters as f
import spray.json.DefaultJsonProtocol.*
import spray.json.JsObject

import scala.util.{Failure, Success, Try}
import scala.util.matching.*

object ModelVersionResource:
  // Regex for matching valid version tags
  final val verRegexString = "^[a-zA-Z0-9][\\w-+.]{0,99}$"
  val verRegex = verRegexString.r

  /**
   * Shorthand for matching paths that contain m/{ns}/{model}/{version}.
   */
  val mvMatcher = "m" / NamespaceResource.nsRegex / ModelResource.modRegex / verRegex

class ModelVersionResource(webhookContr: WebhookController) extends MongoResource:
  import ModelVersionResource.*

  private val innerRoute =
    ignoreTrailingSlash {
      pathLabeled(mvMatcher, "m/:ns/:model/:version") { (ns, model, version) =>
        implicit val mvContext: ModelVersionContext = ModelVersionContext(ns, model, version)
        getModelVersionRoute ~ patchModelVersionRoute ~ deleteModelVersionRoute
      }
    } ~
    // Translate POST rejections
    handleRejections(postRejectionHandler("Invalid namespace, model name, or version. " +
      "Version tags must match regex: " + verRegexString)) {
      post {
        ignoreTrailingSlash {
          pathLabeled(mvMatcher, "m/:ns/:model/:version") { (ns, model, version) =>
            implicit val mvContext: ModelVersionContext = ModelVersionContext(ns, model, version)
            postModelVersionRoute
          }
        }
      }
    }

  /**
   * Outer route handling exceptions and filtering by segment count.
   */
  val route: Route = handleExceptions(mongoExceptionHandler) {
    pathPrefixTest("m" / Segment / Segment / Segment) { (_, _, _) =>
      pathPrefixTest(not("m" / Segment / Segment / Segment / Segment)) {
        innerRoute
      }
    }
  }

  /**
   * Get a model version.
   * @param mvc model version context
   * @return
   */
  def getModelVersionRoute(implicit mvc: ModelVersionContext) =
    get {
      val gmvDir = getModelVersion(true)
      gmvDir { version => complete(StatusCodes.OK, version) }
    }

  /**
   * Post a new model version.
   * @param mvc model version context
   * @return
   */
  def postModelVersionRoute(implicit mvc: ModelVersionContext) =
    post {
      mvc.version match
        case "latest" => complete(StatusCodes.BadRequest, ErrorResponse("Version tag 'latest' is reserved. " +
          "Refer to the API reference for how to set the 'latest' tag for a model."))
        case _ =>
          val entityDirective = validatedEntityOrEmpty(MongoModel.ModelVersion(mvc))
          entityDirective { modelVersion =>
            val transactionFuture = MongoModel.transaction( { session =>
              MongoModel.modelCollection.find(session, modelFilter).collect.flatMap {
                case Seq(model) => MongoModel.modelVersionCollection.insertOne(session, modelVersion)
                case _ => throw NotFoundException()
              }.map(_ => {}).toSingle
            }, None ).toFuture

            onComplete(transactionFuture) {
              case Success(_) =>
                complete(StatusCodes.OK, SuccessResponse(
                  s"Created model version '$modelVersionPath'.")
                )
              case Failure(e: NotFoundException) =>
                complete(StatusCodes.NotFound, ErrorResponse(s"Model '$modelPath' not found."))
              case Failure(e) => throw e
            }
          }
    }

  /**
   * Update an existing model version.
   * @param mvc
   * @return
   */
  def patchModelVersionRoute(implicit mvc: ModelVersionContext) =
    patchEntity[MongoModel.ModelVersion, ModelVersionUpdateModel]
      (MongoModel.modelVersionCollection, modelVersionFilter, modelVersionPath, "model version")

  /**
   * Delete a model version.
   * @param mvc model version context
   * @return
   */
  def deleteModelVersionRoute(implicit mvc: ModelVersionContext) =
    // TODO: add a parameter to force a cascading delete (#38)
    delete {
      requireForceParam {
        mvc.version match
          case "latest" => complete(StatusCodes.BadRequest, ErrorResponse("Version tag 'latest' is a pointer and " +
            "cannot be deleted directly. If you wish to delete the model version under this tag, first obtain its " +
            "real tag via a GET request, and then DELETE it directly. If you want to delete the 'latest' pointer, " +
            "refer to the API documentation for how to do that."))
          case _ =>
            val transactionFuture = MongoModel.transaction( { session =>
              // First find the model version and see if there are any uploaded files or docs.
              // If we want to return somewhat granular error messages, we need a transaction here.
              MongoModel.modelVersionCollection.find(session, modelVersionFilter).collect.flatMap {
                case Seq(mv) if mv.formats.getOrElse(Map()).nonEmpty =>
                  throw NonEmptyEntityException("content")
                case Seq(mv) if mv.docJob.nonEmpty =>
                  throw NonEmptyEntityException("documentation")
                case Seq(_) =>
                  val deleteObs = MongoModel.modelVersionCollection.deleteOne(session, modelVersionFilter)
                    .collect.map {
                    case Seq(result) if result.getDeletedCount == 1 =>
                    case _ => throw NotFoundException()
                  }
                  deleteObs.toSingle
                case _ => throw NotFoundException()
              }
            }, None ).toFuture

            onComplete(transactionFuture) {
              case Success(_) =>
                webhookContr.dispatchWebhook(
                  MongoModel.WebhookAction.ModelVersionDelete,
                  mvc,
                  DateTime.now,
                  JsObject()
                )
                complete(
                  StatusCodes.OK, SuccessResponse(s"Deleted model version '$modelVersionPath'.")
                )
              case Failure(NonEmptyEntityException(what)) =>
                complete(StatusCodes.BadRequest, ErrorResponse(
                  s"Model version '$modelVersionPath' is not empty. Please delete the $what first."))
              case Failure(e: NotFoundException) =>
                complete(StatusCodes.NotFound, ErrorResponse(
                  s"Model version '$modelVersionPath' not found."))
              case Failure(e) => throw e
            }
      }
    }
