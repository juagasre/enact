package eu.assistiot.semantic_repo.core.test.rest

import akka.http.scaladsl.model.*
import akka.http.scaladsl.server.*
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.unmarshalling.FromResponseUnmarshaller
import eu.assistiot.semantic_repo.core.buildinfo.BuildInfo
import eu.assistiot.semantic_repo.core.datamodel.*
import eu.assistiot.semantic_repo.core.rest.resources.*
import eu.assistiot.semantic_repo.core.rest.json.*
import eu.assistiot.semantic_repo.core.test.ApiSpec
import org.scalatest.DoNotDiscover

import scala.reflect.ClassTag

/**
 * Integration tests covering the / and /{namespace} endpoints of the REST API.
 *
 * After the test is completed, namespaces named "test" and "test2" are left in the database.
 */
@DoNotDiscover
class NamespaceSpec extends ApiSpec {
  val nsRoute = Route.seal(NamespaceResource(controllers.webhook).route)

  def createNamespace[T: FromResponseUnmarshaller: ClassTag](name: String, statusCode: StatusCode) =
    Post(s"/m/$name") ~> nsRoute ~> check {
      status should be (statusCode)
      contentType should be (ContentTypes.`application/json`)
      responseAs[T] shouldBe a [T]
    }

  "namespace endpoint (/m/)" should {
    // Creating namespaces
    "create a namespace" in {
      createNamespace[SuccessResponse]("test", StatusCodes.OK)
    }
    "create a namespace with trailing slash" in {
      createNamespace[SuccessResponse]("test2/", StatusCodes.OK)
    }
    "create a namespace with alnum characters and _-" in {
      createNamespace[SuccessResponse]("test-NAMESPACE_123", StatusCodes.OK)
    }
    "not create a namespace with name starting with -" in {
      createNamespace[ErrorResponse]("-test", StatusCodes.BadRequest)
    }
    "not create a namespace with name starting with _" in {
      createNamespace[ErrorResponse]("__test", StatusCodes.BadRequest)
    }
    "not create a duplicate namespace" in {
      createNamespace[ErrorResponse]("test", StatusCodes.Conflict)
    }
    "create a namespace with name with 1 char" in {
      createNamespace[SuccessResponse]("4", StatusCodes.OK)
    }
    "not create a namespace with name containing special chars" in {
      createNamespace[ErrorResponse]("aaa-*", StatusCodes.BadRequest)
    }
    "not create a namespace with name containing non-ASCII chars" in {
      // "grząść", a typical Polish word
      createNamespace[ErrorResponse]("grz%C4%85%C5%9B%C4%87", StatusCodes.BadRequest)
    }
    "not create a namespace with name longer than 100 characters" in {
      createNamespace[ErrorResponse]("0123456789".repeat(10) + "a", StatusCodes.BadRequest)
    }
    "create a namespace with name 100 characters long" in {
      createNamespace[SuccessResponse]("0123456789".repeat(10), StatusCodes.OK)
    }

    // Listing all namespaces
    "list all namespaces" in {
      Get("/m/") ~> nsRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        val response = responseAs[RootInfoClientModel]
        response shouldBe a [RootInfoClientModel]
        val namespaces = response.namespaces.value
        namespaces.totalCount should be (5)
        namespaces.items should contain (NamespaceClientModel("test", None, None))
        namespaces.items should contain (NamespaceClientModel("test-NAMESPACE_123", None, None))
      }
    }

    // Retrieving one namespace
    "retrieve one namespace" in {
      Get("/m/test") ~> nsRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        val response = responseAs[NamespaceClientModel]
        response shouldBe a [NamespaceClientModel]
        response.namespace should be ("test")
      }
    }
    "retrieve one namespace with trailing slash" in {
      Get("/m/test2/") ~> nsRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        val response = responseAs[NamespaceClientModel]
        response shouldBe a [NamespaceClientModel]
      }
    }
    "not retrieve a non-existent namespace" in {
      Get("/m/doesnotexist") ~> nsRoute ~> check {
        status should be (StatusCodes.NotFound)
      }
    }
    "not retrieve a namespace with invalid name" in {
      Get("/m/--") ~> nsRoute ~> check {
        status should be (StatusCodes.NotFound)
      }
    }

    // Deleting namespaces
    "not delete a namespace without the 'force' parameter" in {
      Delete("/m/test-NAMESPACE_123") ~> nsRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val response = responseAs[ErrorResponse]
        response.error should include ("To really perform this action")
        response.error should include ("'force'")
      }
    }
    "not delete a namespace with the 'force' parameter not set to 1" in {
      Delete("/m/test-NAMESPACE_123?force=true") ~> nsRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val response = responseAs[ErrorResponse]
        response.error should include ("To really perform this action")
        response.error should include ("'force'")
      }
    }
    "delete a namespace" in {
      Delete("/m/test-NAMESPACE_123?force=1") ~> nsRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        responseAs[SuccessResponse] shouldBe a [SuccessResponse]
      }
    }
    "delete a namespace with trailing slash" in {
      Delete("/m/" + "0123456789".repeat(10) + "/?force=1") ~> nsRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        responseAs[SuccessResponse] shouldBe a [SuccessResponse]
      }
    }
    "not delete a non-existent namespace" in {
      Delete("/m/doesnotexist?force=1") ~> nsRoute ~> check {
        status should be (StatusCodes.NotFound)
      }
    }
    "not retrieve a deleted namespace" in {
      Get("/m/test-NAMESPACE_123") ~> nsRoute ~> check {
        status should be (StatusCodes.NotFound)
      }
    }
  }
}
