package eu.assistiot.semantic_repo.core.rest.resources

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import eu.assistiot.semantic_repo.core.Exceptions.*
import eu.assistiot.semantic_repo.core.datamodel.{ErrorResponse, MongoModel, SuccessResponse}
import eu.assistiot.semantic_repo.core.documentation.{DocDbUtils, DocStorageUtils}
import eu.assistiot.semantic_repo.core.rest.*
import eu.assistiot.semantic_repo.core.rest.Directives.requireForceParam
import eu.assistiot.semantic_repo.core.rest.json.*
import eu.assistiot.semantic_repo.core.storage.Bucket
import eu.assistiot.semantic_repo.core.{AppConfig, Guardian}
import fr.davit.akka.http.metrics.core.scaladsl.server.HttpMetricsDirectives.*
import org.mongodb.scala.*
import org.mongodb.scala.model.Updates as u

import scala.util.{Failure, Success}

/**
 * Resource handling documentation attached to specific model versions.
 * See also: [[DocSandboxResource]]
 * @param mainSystem main actor system
 */
class DocModelResource(val mainSystem: ActorSystem[Guardian.Command]) extends DocumentationResource:
  val route: Route = handleExceptions(mongoExceptionHandler) {
    pathPrefixLabeled(ModelVersionResource.mvMatcher, "m/:ns/:model/:version") { (ns, model, version) =>
      implicit val mvc: ModelVersionContext = ModelVersionContext(ns, model, version)
      pathPrefixLabeled("doc") {
        pathEndOrSingleSlash {
          deleteModelDocsRoute
        } ~
        serveModelDocsRoute
      } ~
      pathPrefixLabeled("doc_gen") {
        pathEndOrSingleSlash {
          postModelJobRoute
        }
      }
    }
  }

  /**
   * Serve documentation for a model.
   * @param mvc model version context
   * @return
   */
  def serveModelDocsRoute(implicit mvc: AbstractModelVersionContext) =
    mvc.version match
      case "latest" =>
        get {
          val glvDir = getLatestVersion
          glvDir { version =>
            val mvc2 = ModelVersionContext(mvc.ns, mvc.model, version)
            extractUri { uri =>
              // Redirect to the canonical path
              // It would be inefficient to look up the latest version on every request
              // Strip the old .../ns/model/latest/... path and insert /ns/model/someVer instead
              val newPath = uri.path.toString.replaceFirst("/m/" + mvc.path, "/m/" + mvc2.path)
              redirect(
                uri.withPath(Uri.Path(newPath)),
                StatusCodes.TemporaryRedirect
              )
            }
          }
        }
      case _ =>
        /** See [[MongoModel.DocCompilationInfo]] */
        serveDocsRoute("model/" + mvc.path)

  /**
   * Delete documentation for a model.
   * @param mvc model version context
   * @return
   */
  def deleteModelDocsRoute(implicit mvc: AbstractModelVersionContext): Route =
    delete {
      if mvc.version == "latest" then
        complete(StatusCodes.BadRequest,
          ErrorResponse("The use of the 'latest' tag is not allowed for this endpoint."))
      else
        requireForceParam {
          onComplete(DocDbUtils.markModelJobForDeletion) {
            case Success(_) =>
              // Outside transaction, remove the output files
              extractMaterializer { mat =>
                val deleteContentFut =
                  DocStorageUtils.deleteJobContent("model/" + mvc.path, Bucket.DocOutput)(mat)
                onSuccess(deleteContentFut) { _ =>
                  complete(
                    StatusCodes.OK,
                    SuccessResponse(s"Deleted documentation for model version '$modelVersionPath'.")
                  )
                }
              }
            case Failure(_: NotFoundException) => complete(
              StatusCodes.NotFound,
              ErrorResponse(s"There is no documentation for model version '$modelVersionPath'.")
            )
            case Failure(PreValidationException(e: Exception)) => complete(
              StatusCodes.BadRequest,
              ErrorResponse(e.getMessage)
            )
            case Failure(e) => throw e
          }
          // The source files will be removed later by the cleanup actor.
          // For this purpose, we leave an appropriate entry in the doc job collection in Mongo.
        }
    }

  /**
   * Post a new documentation generation job for a model.
   * @param mvc model version context
   * @return
   */
  def postModelJobRoute(implicit mvc: AbstractModelVersionContext): Route =
    val gmvDir = getModelVersion(false)
    gmvDir { _ =>
      parameter("overwrite".optional) { owParam =>
        val overwrite = owParam match
          case Some("1") => true
          case _ => false
        postDocJobRoute(Some(mvc), overwrite)
      }
    }
