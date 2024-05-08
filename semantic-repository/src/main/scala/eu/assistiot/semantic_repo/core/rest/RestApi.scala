package eu.assistiot.semantic_repo.core.rest

import akka.actor.typed.ActorSystem
import akka.http.javadsl.model.HttpMethods.*
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.{EntityStreamSizeException, StatusCodes}
import akka.http.scaladsl.server.*
import akka.http.scaladsl.server.Directives.*
import akka.util.Timeout
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import com.typesafe.scalalogging.LazyLogging
import eu.assistiot.semantic_repo.core.{ActorSystemUser, AppConfig, ControllerContainer, Main, Reaper}
import eu.assistiot.semantic_repo.core.datamodel.{ErrorResponse, SuccessResponse, InfoResponse}
import eu.assistiot.semantic_repo.core.rest.json.JsonSupport
import eu.assistiot.semantic_repo.core.rest.resources.*
import fr.davit.akka.http.metrics.core.scaladsl.server.HttpMetricsDirectives.*

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.Success

trait RestApi extends ActorSystemUser, JsonSupport, LazyLogging:
  implicit val timeout: Timeout = Timeout(10.seconds)

  /**
   * Controller container, to be implemented by Main.
   */
  def controllers: ControllerContainer

  val apiExceptionHandler = ExceptionHandler {
    case e =>
      // Catch-all to prevent semrepo from returning internal details to the user
      logger.error("Unknown error", e)
      complete(StatusCodes.InternalServerError, ErrorResponse("Internal server error"))
  }

  val api: Route = handleExceptions(apiExceptionHandler) {
    cors(CorsSettings(AppConfig.getConfig)) {
      pathPrefixLabeled("v1") {
        concat(
          // Info (/v1/version, /v1/info, /v1/health)
          InfoResource.route,
          // Swagger (/v1/api-export)
          SwaggerResource.route,
          // Doc sandbox (/v1/doc_gen)
          new DocSandboxResource(system).route,
          // Webhooks (/v1/webhook)
          new WebhookResource(controllers.webhook).route,
          // Content (/v1/c/... and /v1/m/*/*/*/content)
          new ContentResource(controllers.webhook).route,
          // Docs for models (/v1/m/*/*/*/doc and /v1/m/*/*/*/doc_gen)
          new DocModelResource(system).route,
          // Model versions (/v1/m/*/*/*)
          new ModelVersionResource(controllers.webhook).route,
          // Models (/v1/m/*/*)
          new ModelResource(controllers.webhook).route,
          // Namespaces (/v1/m/*)
          new NamespaceResource(controllers.webhook).route,
        )
      } ~
      // /version, /info, /health
      InfoResource.route ~
      // /metrics
      new PrometheusResource(controllers.metrics).route
    }
  }
