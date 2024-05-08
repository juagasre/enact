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
 * Integration tests covering the /{namespace}/{model}/{version} endpoints of the REST API.
 *
 * After the test is completed, the following model versions are in the database:
 *  - /test/model/1.0.0
 *  - /test/model/1.1.0
 *  - /test/model/1
 *  - /test/model/1.0.0-alpha+Debian_buster
 *  - /test/model2/1.0.0
 *  - /test2/model/1.0.0
 *
 *  Latest pointers:
 *  - /test/model/1.1.0
 *  - /test/model2/2.0.0 (does not exist)
 */
@DoNotDiscover
class ModelVersionSpec extends ApiSpec:
  val modelRoute = Route.seal(ModelResource(controllers.webhook).route)
  val mvRoute = Route.seal(ModelVersionResource(controllers.webhook).route)
  
  def modifyVersion[T: FromResponseUnmarshaller: ClassTag]
  (method: RequestBuilder, name: String, statusCode: StatusCode, payload: Option[String] = None): T =
    val request = payload match
      case None => method(s"/m/$name")
      case Some(p) => method(
        s"/m/$name",
        HttpEntity(ContentTypes.`application/json`, p.replace('\'', '"'))
      )
    request ~> mvRoute ~> check {
      status should be (statusCode)
      contentType should be (ContentTypes.`application/json`)
      responseAs[T] shouldBe a [T]
      responseAs[T]
    }

  "model version endpoint" should {
    // Creating versions
    "create a model version" in {
      modifyVersion[SuccessResponse](Post, "test/model/1.0.0", StatusCodes.OK)
    }
    "create a model version with empty JSON payload" in {
      modifyVersion[SuccessResponse](Post, "test/model/empty", StatusCodes.OK, Some("{}"))
    }
    "create a model version with full JSON payload" in {
      modifyVersion[SuccessResponse](Post, "test/model/full", StatusCodes.OK,
        Some("{'defaultFormat': 'json'}"))
    }
    "not create a version with a JSON payload attempting to unset a property" in {
      modifyVersion[ErrorResponse](Post, "test/model/bad1", StatusCodes.BadRequest,
        Some("{'defaultFormat': '@unset'}"))
    }
    "not create a version with a JSON payload with invalid property value" in {
      modifyVersion[ErrorResponse](Post, "test/model/bad2", StatusCodes.BadRequest,
        Some("{'defaultFormat': '-json'}")) // format must not start with "-"
    }
    "create a model version with trailing slash" in {
      modifyVersion[SuccessResponse](Post, "test/model/1.1.0/", StatusCodes.OK)
    }
    "create same version tag in a different model" in {
      modifyVersion[SuccessResponse](Post, "test/model2/1.0.0", StatusCodes.OK)
    }
    "create same version tag in a different namespace" in {
      modifyVersion[SuccessResponse](Post, "test2/model/1.0.0", StatusCodes.OK)
    }
    "create a model version with special characters" in {
      modifyVersion[SuccessResponse](Post, "test/model/1.0.0-alpha+Debian_buster", StatusCodes.OK)
    }
    "create a model version with single character tag" in {
      modifyVersion[SuccessResponse](Post, "test/model/a", StatusCodes.OK)
    }
    "create a model version with single digit tag" in {
      modifyVersion[SuccessResponse](Post, "test/model/1", StatusCodes.OK)
    }
    "-_+.".foreach { c =>
      "not create a model version with tag starting with " + c in {
        modifyVersion[ErrorResponse](Post, s"test/model/${c}1", StatusCodes.BadRequest)
      }
    }
    "not create a model version with name containing special chars" in {
      modifyVersion[ErrorResponse](Post, "test/model/aaa-*", StatusCodes.BadRequest)
    }
    "not create a model version with name containing non-ASCII chars" in {
      // "grząść", a typical Polish word
      modifyVersion[ErrorResponse](Post, "test/model/grz%C4%85%C5%9B%C4%87", StatusCodes.BadRequest)
    }
    "not create a model version with name longer than 100 characters" in {
      modifyVersion[ErrorResponse](Post, "test/model/" + "0123456789".repeat(10) + "a", StatusCodes.BadRequest)
    }
    "create a model version with name 100 characters long" in {
      modifyVersion[SuccessResponse](Post, "test/model/" + "0123456789".repeat(10), StatusCodes.OK)
    }
    "not create a model version with reserved version tag (latest)" in {
      Post("/m/test/model/latest") ~> mvRoute ~> check {
        status should be (StatusCodes.BadRequest)
        contentType should be (ContentTypes.`application/json`)
        val response = responseAs[ErrorResponse]
        response shouldBe an [ErrorResponse]
        response.error should include ("reserved")
      }
    }
    "not create a model version in a non-existent model" in {
      Post("/m/test/doesnotexist/1.0") ~> mvRoute ~> check {
        status should be (StatusCodes.NotFound)
        contentType should be (ContentTypes.`application/json`)
        responseAs[ErrorResponse] shouldBe an [ErrorResponse]
      }
    }
  }

  "model endpoint" should {
    "set latest tag to an existing version of a model" in {
      Patch("/m/test/model",
        HttpEntity(ContentTypes.`application/json`, "{\"latestVersion\": \"1.1.0\"}")
      ) ~> modelRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }
    "set latest tag to a non-existent version of a model" in {
      Patch("/m/test/model2",
        HttpEntity(ContentTypes.`application/json`, "{\"latestVersion\": \"2.0.0\"}")
      ) ~> modelRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }
    "list versions of a model" in {
      Get("/m/test/model") ~> modelRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        val response = responseAs[ModelClientModel]
        response shouldBe a [ModelClientModel]
        val versions = response.versions.value
        versions.totalCount should be (8)
        versions.items.toArray.length should be (8)
        // The None, None at the end are very important – that means the model version details are not
        // returned in this endpoint.
        versions.items should contain (ModelVersionClientModel("1.0.0", "model", "test", None, None, None, None))
        versions.items should contain (ModelVersionClientModel("1.1.0", "model", "test", None, None, None, None))
        versions.items should contain (ModelVersionClientModel("full", "model", "test", None, Some("json"), None, None))
      }
    }
  }

  "model version endpoint" should {
    // Retrieving one version
    "retrieve one model version" in {
      Get("/m/test/model/1.0.0") ~> mvRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        val response = responseAs[ModelVersionClientModel]
        response shouldBe a [ModelVersionClientModel]
        response.version should be ("1.0.0")
        response.model should be ("model")
        response.namespace should be ("test")
        response.formats.value should be (Map())
        response.defaultFormat should be (None)
      }
    }
    "retrieve model version with default format set on POST" in {
      Get("/m/test/model/full") ~> mvRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        val response = responseAs[ModelVersionClientModel]
        response.version should be ("full")
        response.defaultFormat.value should be ("json")
      }
    }
    "retrieve model version with trailing slash" in {
      Get("/m/test2/model/1.0.0") ~> mvRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        val response = responseAs[ModelVersionClientModel]
        response shouldBe a [ModelVersionClientModel]
        response.version should be ("1.0.0")
        response.model should be ("model")
        response.namespace should be ("test2")
        response.formats.value should be (Map())
        response.defaultFormat should be (None)
      }
    }
    "not retrieve a non-existent model version" in {
      Get("/m/test/model/doesnotexist") ~> mvRoute ~> check {
        status should be (StatusCodes.NotFound)
      }
    }
    "not retrieve a model version in invalid namespace" in {
      Get("/t/model/1.0") ~> mvRoute ~> check {
        status should be (StatusCodes.NotFound)
      }
    }
    "retrieve a valid 'latest' version" in {
      Get("/m/test/model/latest") ~> mvRoute ~> check {
        status should be (StatusCodes.OK)
        val response = responseAs[ModelVersionClientModel]
        response.version should be ("1.1.0")
        response.model should be ("model")
        response.namespace should be ("test")
        response.formats.value should be (Map())
        response.defaultFormat should be (None)
      }
    }
    "retrieve a valid 'latest' version with trailing slash" in {
      Get("/m/test/model/latest/") ~> mvRoute ~> check {
        status should be (StatusCodes.OK)
        responseAs[ModelVersionClientModel] shouldBe a [ModelVersionClientModel]
      }
    }
    "not retrieve a broken 'latest' pointer" in {
      Get("/m/test/model2/latest") ~> mvRoute ~> check {
        status should be (StatusCodes.NotFound)
        val response = responseAs[ErrorResponse]
        response.error should include ("test/model2/2.0.0")
        response.error should include ("Please update the pointer")
      }
    }
    "not retrieve an unset 'latest' pointer" in {
      Get("/m/test2/model/latest") ~> mvRoute ~> check {
        status should be (StatusCodes.NotFound)
        val response = responseAs[ErrorResponse]
        response.error should include ("'latestVersion' pointer is not set")
        response.error should include ("test2/model")
      }
    }
    "not retrieve the 'latest' pointer on a non-existent model" in {
      Get("/m/test/doesnotexist/latest") ~> mvRoute ~> check {
        status should be (StatusCodes.NotFound)
        val response = responseAs[ErrorResponse]
        response.error should include ("test/doesnotexist")
      }
    }

    // Patching model versions
    "set a property on a model version" in {
      modifyVersion[SuccessResponse](Patch, "test/model/empty", StatusCodes.OK, Some("{'defaultFormat': 'csv'}"))
    }
    "retrieve model version with field set in PATCH" in {
      Get("/m/test/model/empty") ~> mvRoute ~> check {
        val response = responseAs[ModelVersionClientModel]
        response.defaultFormat.value should be ("csv")
      }
    }
    "unset a property on a model" in {
      modifyVersion[SuccessResponse](Patch, "test/model/full", StatusCodes.OK, Some("{'defaultFormat': '@unset'}"))
    }
    "retrieve model version with field unset in PATCH" in {
      Get("/m/test/model/full") ~> mvRoute ~> check {
        val response = responseAs[ModelVersionClientModel]
        response.defaultFormat should be (None)
      }
    }
    "return an error on empty patch body" in {
      modifyVersion[ErrorResponse](Patch, "test/model/1.0.0", StatusCodes.BadRequest)
    }
    "ignore empty update JSON" in {
      val response = modifyVersion[SuccessResponse](Patch, "test/model/1.0.0", StatusCodes.OK, Some("{}"))
      response.message should include ("No updates requested")
    }
    "set a property on a model version – no update needed" in {
      val response = modifyVersion[SuccessResponse](Patch, "test/model/empty", StatusCodes.OK,
        Some("{'defaultFormat': 'csv'}"))
      response.message should include ("no update needed")
    }
    "not update a non-existent model version" in {
      val response = modifyVersion[ErrorResponse](Patch, "test/model/doesnotexist", StatusCodes.NotFound,
        Some("{'defaultFormat': 'csv'}"))
      response.error should include ("Could not find model version")
      response.error should include ("test/model/doesnotexist")
    }

    // Deleting model versions
    "not delete a version without the 'force' parameter" in {
      Delete("/m/test/model/a") ~> mvRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val response = responseAs[ErrorResponse]
        response.error should include ("To really perform this action")
        response.error should include ("'force'")
      }
    }
    "not delete a version with the 'force' parameter not set to 1" in {
      Delete("/m/test/model/a?force=true") ~> mvRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val response = responseAs[ErrorResponse]
        response.error should include ("To really perform this action")
        response.error should include ("'force'")
      }
    }
    "delete a version" in {
      Delete("/m/test/model/a?force=1") ~> mvRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        responseAs[SuccessResponse] shouldBe a [SuccessResponse]
      }
    }
    "delete a version with trailing slash" in {
      Delete("/m/test/model/" + "0123456789".repeat(10) + "/?force=1") ~> mvRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        responseAs[SuccessResponse] shouldBe a [SuccessResponse]
      }
    }
    "not delete a non-existent version" in {
      Delete("/m/test/model/doesnotexist?force=1") ~> mvRoute ~> check {
        status should be (StatusCodes.NotFound)
      }
    }
    "not delete a version of a model in non-existent namespace" in {
      Delete("/doesnotexist/model/1.0.0?force=1") ~> mvRoute ~> check {
        status should be (StatusCodes.NotFound)
      }
    }
    "not delete a version of a non-existent model" in {
      Delete("/m/test/doesnotexist/1.0.0?force=1") ~> mvRoute ~> check {
        status should be (StatusCodes.NotFound)
      }
    }
    "not retrieve a deleted version" in {
      Get("/m/test/model/a?force=1") ~> mvRoute ~> check {
        status should be (StatusCodes.NotFound)
      }
    }
    "not delete the 'latest' tag" in {
      Delete("/m/test/model/latest?force=1") ~> mvRoute ~> check {
        status should be (StatusCodes.BadRequest)
        contentType should be (ContentTypes.`application/json`)
        val response = responseAs[ErrorResponse]
        response shouldBe an [ErrorResponse]
        response.error should include ("pointer")
      }
    }
  }
