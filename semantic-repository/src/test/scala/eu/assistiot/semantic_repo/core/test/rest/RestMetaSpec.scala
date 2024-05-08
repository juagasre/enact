package eu.assistiot.semantic_repo.core.test.rest

import akka.http.scaladsl.model.*
import akka.http.scaladsl.server.*
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import eu.assistiot.semantic_repo.core.buildinfo.BuildInfo
import eu.assistiot.semantic_repo.core.controller.MetricsController
import eu.assistiot.semantic_repo.core.datamodel.*
import eu.assistiot.semantic_repo.core.rest.resources.*
import eu.assistiot.semantic_repo.core.test.ApiSpec

import java.nio.file.{Files, Paths}

/**
 * Tests the meta endpoints of the API.
 */
class RestMetaSpec extends ApiSpec {
  val metricsController = new MetricsController()
  val prometheusResource = new PrometheusResource(metricsController)

  "info endpoint" should {
    // GET /version
    "return an info response" in {
      Get("/info") ~> InfoResource.route ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        responseAs[InfoResponse] shouldBe a [InfoResponse]
      }
    }
  }

  "version endpoint" should {
    // GET /version
    "return a version response" in {
      Get("/version") ~> InfoResource.route ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`text/plain(UTF-8)`)
        responseAs[String] should be (BuildInfo.version)
      }
    }
  }
  
  "health endpoint" should {
    // GET /health
    "return a 200 response" in {
      Get("/health") ~> InfoResource.route ~> check {
        status should be (StatusCodes.OK)
      }
    }
  }

  "metrics endpoint" should {
    // GET /metrics
    "return Prometheus metrics" in {
      Get("/metrics") ~> prometheusResource.route ~> check {
        status should be (StatusCodes.OK)
        contentType.mediaType should be (MediaType.custom("text/plain; version=0.0.4", false))
        contentType.charsetOption should be(Some(HttpCharsets.`UTF-8`))
      }
    }
  }

  "swagger endpoint" should {
    // GET /api-export/docs/
    "return HTML documentation" in {
      Get("/api-export/docs/") ~> SwaggerResource.route ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`text/html(UTF-8)`)
      }
    }

    // GET /api-export/openapi
    "return JSON API definition" in {
      Get("/api-export/openapi") ~> SwaggerResource.route ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        Files.write(Paths.get("swagger.json"), responseAs[String].getBytes)
      }
    }

    "return JSON API definition (alternative endpoint)" in {
      Get("/api-export/swagger.json") ~> SwaggerResource.route ~> check {
        status should be(StatusCodes.OK)
        contentType should be(ContentTypes.`application/json`)
        Files.write(Paths.get("swagger.json"), responseAs[String].getBytes)
      }
    }

    // GET /sw/api-docs/swagger.yaml
    "return YAML API definition" in {
      Get("/api-export/swagger.yaml") ~> SwaggerResource.route ~> check {
        status should be (StatusCodes.OK)
        mediaType should be (MediaType.customWithFixedCharset(
          "application", "yaml", HttpCharsets.`UTF-8`
        ))
        Files.write(Paths.get("swagger.yaml"), responseAs[String].getBytes)
      }
    }
  }
}
