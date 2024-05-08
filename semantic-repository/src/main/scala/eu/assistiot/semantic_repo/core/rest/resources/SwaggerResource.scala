package eu.assistiot.semantic_repo.core.rest.resources

import akka.http.scaladsl.model.*
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import fr.davit.akka.http.metrics.core.scaladsl.server.HttpMetricsDirectives.*

import scala.io.Codec

/**
 * Meta-resource for Swagger.
 */
object SwaggerResource extends Resource:
  // Must specify the encoding explicitly to avoid platform variability
  implicit val codec: Codec = Codec("UTF-8")

  private lazy val swaggerJson: String =
    val yamlReader = new ObjectMapper(new YAMLFactory)
    val obj = yamlReader.readValue(swaggerYaml, classOf[Any])
    val jsonWriter: ObjectMapper = new ObjectMapper
    jsonWriter.writeValueAsString(obj)

  private lazy val swaggerYaml: String =
    val source = scala.io.Source.fromResource("swagger.yaml")
    try source.getLines mkString "\n" finally source.close()

  private val yamlContentType = ContentType(
    MediaType.customWithFixedCharset(
      "application",
      "yaml",
      HttpCharsets.`UTF-8`
    )
  )

  val route: Route = pathPrefixLabeled("api-export") {
    pathPrefixLabeled("openapi") {
      complete(
        StatusCodes.OK,
        HttpEntity(ContentTypes.`application/json`, swaggerJson),
      )
    } ~
    // Alternative paths for definitions: api-export/swagger.yaml and api-export/swagger.json
    pathLabeled("swagger.yaml") {
      complete(
        StatusCodes.OK,
        HttpEntity(yamlContentType, swaggerYaml),
      )
    } ~
    pathLabeled("swagger.json") {
      complete(
        StatusCodes.OK,
        HttpEntity(ContentTypes.`application/json`, swaggerJson),
      )
    } ~
    // Swagger UI
    pathPrefixLabeled("docs") {
      pathEnd {
        extractUri { uri =>
          redirect(uri.copy(path = uri.path + "/"), StatusCodes.TemporaryRedirect)
        }
      } ~
      pathSingleSlash {
        getFromResource("swagger-ui/index.html")
      } ~
      getFromResourceDirectory("swagger-ui")
    }
  }
