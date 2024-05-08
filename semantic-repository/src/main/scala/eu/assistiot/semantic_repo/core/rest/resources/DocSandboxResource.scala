package eu.assistiot.semantic_repo.core.rest.resources

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.FileInfo
import eu.assistiot.semantic_repo.core.datamodel.MongoModel.DocCompilationInfo
import eu.assistiot.semantic_repo.core.datamodel.{ErrorResponse, MongoModel, SuccessResponse}
import eu.assistiot.semantic_repo.core.documentation.{DocDbUtils, DocPluginRegistry}
import eu.assistiot.semantic_repo.core.rest.json.*
import eu.assistiot.semantic_repo.core.{AppConfig, Guardian}
import fr.davit.akka.http.metrics.core.scaladsl.server.HttpMetricsDirectives.*
import org.bson.types.ObjectId

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Resource for the documentation generation sandbox.
 * See also: [[DocModelResource]]
 * @param mainSystem main actor system
 */
class DocSandboxResource(val mainSystem: ActorSystem[Guardian.Command]) extends DocumentationResource:
  val route: Route = handleExceptions(mongoExceptionHandler) {
    pathPrefixLabeled("doc_gen") {
      pathEndOrSingleSlash {
        postSandboxJobRoute ~ getDocMetaRoute
      } ~
      pathPrefixLabeled(ObjectIdMatcher, ":id") { jobId =>
        pathEndOrSingleSlash {
          getSandboxJobStatusRoute(jobId)
        } ~
        pathPrefixLabeled("doc") {
          serveSandboxDocsRoute(jobId)
        }
      }
    }
  }

  /**
   * Get the metadata about available doc plugins.
   * @return
   */
  def getDocMetaRoute: Route =
    get {
      complete(StatusCodes.OK, DocPluginRegistry.enabledPlugins)
    }

  /**
   * Get the status of a sandbox job.
   * @param jobId job identifier
   * @return
   */
  def getSandboxJobStatusRoute(jobId: ObjectId) =
    get {
      onSuccess(DocDbUtils.getSandboxJob(jobId)) {
        case null => complete(StatusCodes.NotFound,
          ErrorResponse(s"Documentation compilation job with ID '$jobId' could not be found."))
        case job => complete(StatusCodes.OK, job)
      }
    }

  /**
   * Serve documentation for a sandbox doc job.
   * @param jobId job identifier
   * @return
   */
  def serveSandboxDocsRoute(jobId: ObjectId) = serveDocsRoute("sandbox/" + jobId)

  /**
   * Post a new sandbox documentation job.
   * @return
   */
  def postSandboxJobRoute: Route = postDocJobRoute(None, false)
