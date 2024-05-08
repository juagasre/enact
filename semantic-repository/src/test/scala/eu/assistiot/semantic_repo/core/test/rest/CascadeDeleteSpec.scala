package eu.assistiot.semantic_repo.core.test.rest

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.*
import akka.http.scaladsl.server.*
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.unmarshalling.FromResponseUnmarshaller
import eu.assistiot.semantic_repo.core.{AppConfig, Guardian}
import eu.assistiot.semantic_repo.core.buildinfo.BuildInfo
import eu.assistiot.semantic_repo.core.datamodel.*
import eu.assistiot.semantic_repo.core.rest.resources.*
import eu.assistiot.semantic_repo.core.rest.json.*
import eu.assistiot.semantic_repo.core.test.ApiSpec
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.Futures.{interval, timeout}

import java.io.File
import scala.concurrent.duration.*

/**
 * Tests for the (non-)cascading deletes of namespaces and models.
 */
@DoNotDiscover
class CascadeDeleteSpec extends ApiSpec:
  val docRoute = Route.seal(DocModelResource(systemInternal).route)
  val contentRoute = Route.seal(ContentResource(controllers.webhook).route)
  val nsRoute = Route.seal(NamespaceResource(controllers.webhook).route)
  val modelRoute = Route.seal(ModelResource(controllers.webhook).route)
  val mvRoute = Route.seal(ModelVersionResource(controllers.webhook).route)

  def getContentEntity =
    val file = new File(getClass.getResource("/small.json").toURI)
    Multipart.FormData.fromFile("content", ContentTypes.`application/json`, file)

  def getDocsEntity =
    val file = new File(getClass.getResource("/docs/simple_md/README.md").toURI)
    Multipart.FormData.fromFile("content", ContentTypes.`application/octet-stream`, file)

  // Prepare namespaces & models for testing
  "REST endpoints" should {
    "create namespaces" in {
      Post("/m/ns_del_1") ~> nsRoute ~> check {
        status should be (StatusCodes.OK)
      }
      Post("/m/ns_del_2") ~> nsRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }
    "create models" in {
      Post("/m/ns_del_1/m_1") ~> modelRoute ~> check {
        status should be (StatusCodes.OK)
      }
      Post("/m/ns_del_1/m_2") ~> modelRoute ~> check {
        status should be (StatusCodes.OK)
      }
      Post("/m/ns_del_2/m_1") ~> modelRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }
    "create model versions" in {
      Post("/m/ns_del_1/m_1/1.0") ~> mvRoute ~> check {
        status should be (StatusCodes.OK)
      }
      Post("/m/ns_del_1/m_1/1.1") ~> mvRoute ~> check {
        status should be (StatusCodes.OK)
      }
      Post("/m/ns_del_2/m_1/1.0") ~> mvRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }

    "upload content" in {
      Post("/m/ns_del_1/m_1/1.0/content?format=application/json", getContentEntity) ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
      }
      Post("/m/ns_del_1/m_1/1.0/content?format=json-2", getContentEntity) ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
      }
      Post("/m/ns_del_2/m_1/1.0/content?format=application/json", getContentEntity) ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }

    "upload documentation" in {
      Post("/m/ns_del_2/m_1/1.0/doc_gen?plugin=markdown", getDocsEntity) ~> docRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }
  }

  // Not delete non-empty entities
  "namespace endpoint" should {
    "not delete non-empty namespace" in {
      Delete("/m/ns_del_2?force=1") ~> nsRoute ~> check {
        status should be (StatusCodes.BadRequest)
        contentType should be (ContentTypes.`application/json`)
        val e: ErrorResponse = responseAs[ErrorResponse]
        e.error should include ("is not empty")
        e.error should include ("delete the models first")
      }
    }
  }

  "model endpoint" should {
    "not delete non-empty model" in {
      Delete("/m/ns_del_2/m_1?force=1") ~> modelRoute ~> check {
        status should be (StatusCodes.BadRequest)
        contentType should be (ContentTypes.`application/json`)
        val e: ErrorResponse = responseAs[ErrorResponse]
        e.error should include ("is not empty")
        e.error should include ("delete the model versions first")
      }
    }
  }

  "model version endpoint" should {
    "not delete non-empty model version (content)" in {
      Delete("/m/ns_del_2/m_1/1.0?force=1") ~> mvRoute ~> check {
        status should be (StatusCodes.BadRequest)
        contentType should be (ContentTypes.`application/json`)
        val e: ErrorResponse = responseAs[ErrorResponse]
        e.error should include ("is not empty")
        e.error should include ("delete the content first")
      }
    }
  }

  // Manually remove child entities
  "content endpoint" should {
    "delete content" in {
      Delete("/m/ns_del_2/m_1/1.0/content?format=application/json&force=1") ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }
  }

  "model version endpoint" should {
    "not delete non-empty model version (documentation)" in {
      Delete("/m/ns_del_2/m_1/1.0?force=1") ~> mvRoute ~> check {
        status should be (StatusCodes.BadRequest)
        contentType should be (ContentTypes.`application/json`)
        val e: ErrorResponse = responseAs[ErrorResponse]
        e.error should include ("is not empty")
        e.error should include ("delete the documentation first")
      }
    }
  }

  "documentation endpoint" should {
    "delete docs" in {
      // first try may be rejected because the docs are being generated
      eventually(timeout(15.seconds), interval(2.seconds)) {
        Delete("/m/ns_del_2/m_1/1.0/doc?force=1") ~> docRoute ~> check {
          status should be (StatusCodes.OK)
        }
      }
    }
  }

  "model version endpoint" should {
    "delete model version" in {
      Delete("/m/ns_del_2/m_1/1.0?force=1") ~> mvRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }
  }

  // Now it should be possible to delete the parents
  "model endpoint" should {
    "delete emptied model" in {
      Delete("/m/ns_del_2/m_1?force=1") ~> modelRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }
  }

  "namespace endpoint" should {
    "delete emptied namespace" in {
      Delete("/m/ns_del_2?force=1") ~> nsRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }
    // TODO: cascading deletion
  }
