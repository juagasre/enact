package eu.assistiot.semantic_repo.core.rest.resources

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.{DateTime, StatusCodes}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.*
import com.mongodb.client.result.DeleteResult
import eu.assistiot.semantic_repo.core.Exceptions.*
import eu.assistiot.semantic_repo.core.controller.WebhookController
import eu.assistiot.semantic_repo.core.datamodel.*
import eu.assistiot.semantic_repo.core.rest.*
import eu.assistiot.semantic_repo.core.rest.Directives.*
import eu.assistiot.semantic_repo.core.rest.json.*
import eu.assistiot.semantic_repo.core.rest.json.update.ModelUpdateModel
import fr.davit.akka.http.metrics.core.scaladsl.server.HttpMetricsDirectives.*
import org.mongodb.scala.*
import org.mongodb.scala.model.Filters as f
import org.mongodb.scala.model.Projections as p
import org.mongodb.scala.result.UpdateResult
import spray.json.DefaultJsonProtocol.*
import spray.json.JsObject

import scala.util.{Failure, Success, Try}
import scala.util.matching.*

object ModelResource:
  // Regex for matching valid model names
  final val modRegexString = NamespaceResource.nsRegexString
  val modRegex = modRegexString.r

class ModelResource(webhookContr: WebhookController) extends MongoResource:
  import ModelResource.*

  private val innerRoute =
    ignoreTrailingSlash {
      pathLabeled("m" / NamespaceResource.nsRegex / modRegex, "m/:ns/:model") { (ns, model) =>
        implicit val mc: ModelContext = ModelContext(ns, model)
        getModelRoute ~ patchModelRoute ~ deleteModelRoute
      }
    } ~
    // Translate POST rejections
    handleRejections(postRejectionHandler("Invalid namespace or model name. " +
      "Namespace and model names must match regex: " + modRegexString)) {
      post {
        ignoreTrailingSlash {
          pathLabeled("m" / NamespaceResource.nsRegex / modRegex, "m/:ns/:model") { (ns, model) =>
            implicit val mc: ModelContext = ModelContext(ns, model)
            postModelRoute
          }
        }
      }
    }

  /**
   * Outer route handling exceptions and filtering by segment count.
   */
  val route: Route = handleExceptions(mongoExceptionHandler) {
    pathPrefixTest("m" / Segment / Segment) { (_, _) =>
      pathPrefixTest(not("m" / Segment / Segment / Segment)) {
        innerRoute
      }
    }
  }

  /**
   * Get a model by name
   * @param mc model context
   * @return
   */
  def getModelRoute(implicit mc: AbstractModelContext) =
    get {
      // Separated directive call to avoid strange compiler bugs (??)
      // Otherwise the compiler thinks that the inner route is actually the implicit parameter of the method...
      val paramsDir = extractMongoSetParams[MongoModel.ModelVersion]
      paramsDir { params =>
        // TODO: only return a list of model versions when requested
        val modelFuture = MongoModel.modelCollection
          .find(modelFilter)
          .first.toFuture
        val versionsFuture = MongoModel.modelVersionCollection.findToSet(
          params,
          modelVersionFilter,
          Some(p.fields(p.include(
            "_id", "version", "modelName", "namespaceName", "defaultFormat", "metadata"
          )))
        ).toFuture

        onSuccess(modelFuture) { _ match
          case null => complete(StatusCodes.NotFound, ErrorResponse(s"Model '$modelPath' not found."))
          case model =>
            onSuccess(versionsFuture) { versionSet =>
              complete(StatusCodes.OK, (model, Some(versionSet)))
            }
        }
      }
    }

  /**
   * Post a new model.
   * @param mc model context
   * @return
   */
  def postModelRoute(implicit mc: AbstractModelContext) =
    post {
      val entityDirective = validatedEntityOrEmpty(MongoModel.Model(mc))
      entityDirective { model =>
        val transactionFuture = MongoModel.transaction( { session =>
          MongoModel.namespaceCollection.find(session, namespaceFilter).collect.flatMap {
            case Seq(ns) => MongoModel.modelCollection.insertOne(session, model)
            case _ => throw NotFoundException()
          }.map(_ => {}).toSingle
        }, None ).toFuture

        onComplete(transactionFuture) {
          case Success(_) =>
            complete(StatusCodes.OK, SuccessResponse(s"Created model '$modelPath'."))
          case Failure(e: NotFoundException) =>
            complete(StatusCodes.NotFound, ErrorResponse(s"Namespace '$namespacePath' not found."))
          case Failure(e) => throw e
        }
      }
    }

  /**
   * Update an existing model.
   * @param mc model context
   * @return
   */
  def patchModelRoute(implicit mc: AbstractModelContext) =
    patchEntity[MongoModel.Model, ModelUpdateModel](MongoModel.modelCollection, modelFilter, modelPath, "model")

  /**
   * Delete a model.
   * @param mc model context
   * @return
   */
  def deleteModelRoute(implicit mc: AbstractModelContext) =
    // TODO: add a parameter to force a cascading delete (#38)
    delete {
      requireForceParam {
        val transactionFuture = MongoModel.transaction( { session =>
          MongoModel.modelVersionCollection.find(
            session, modelVersionFilter
          ).collect.flatMap {
            case s if s.nonEmpty => throw NonEmptyEntityException("model versions")
            case _ =>
              MongoModel.modelCollection.deleteOne(session, modelFilter).collect.map {
                case Seq(result) if result.getDeletedCount == 1 =>
                case _ => throw NotFoundException()
              }
          }.toSingle
        }, None ).toFuture

        onComplete(transactionFuture) {
          case Success(_) =>
            webhookContr.dispatchWebhook(
              MongoModel.WebhookAction.ModelDelete,
              mc,
              DateTime.now,
              JsObject()
            )
            complete(StatusCodes.OK, SuccessResponse(s"Deleted model '$modelPath'."))
          case Failure(NonEmptyEntityException(what)) =>
            complete(StatusCodes.BadRequest,
              ErrorResponse(s"Model '$modelPath' is not empty. Please delete the $what first."))
          case Failure(e: NotFoundException) =>
            complete(StatusCodes.NotFound, ErrorResponse(s"Model '$modelPath' not found."))
          case Failure(e) => throw e
        }
      }
    }
