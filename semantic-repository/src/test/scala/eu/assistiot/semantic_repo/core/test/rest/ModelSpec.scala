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
 * Integration tests covering the /{namespace}/{model} endpoints of the REST API.
 *
 * After the test is completed, the following models are left in the DB:
 *  - "test/model",
 *  - "test/model2",
 *  - "test2/model"
 */
@DoNotDiscover
class ModelSpec extends ApiSpec:
  val nsRoute = Route.seal(NamespaceResource(controllers.webhook).route)
  val modelRoute = Route.seal(ModelResource(controllers.webhook).route)
  
  def modifyModel[T: FromResponseUnmarshaller: ClassTag]
  (method: RequestBuilder, name: String, statusCode: StatusCode, payload: Option[String] = None): T =
    val request = payload match
      case None => method(s"/m/$name")
      case Some(p) => method(
        s"/m/$name",
        HttpEntity(ContentTypes.`application/json`, p.replace('\'', '"'))
      )
    request ~> modelRoute ~> check {
      status should be (statusCode)
      contentType should be (ContentTypes.`application/json`)
      responseAs[T] shouldBe a [T]
      responseAs[T]
    }

  "model endpoint" should {
    // Creating models
    "create a model" in {
      modifyModel[SuccessResponse](Post, "test/model", StatusCodes.OK)
    }
    "create a model with empty JSON payload" in {
      modifyModel[SuccessResponse](Post, "test/model_json_empty", StatusCodes.OK, Some("{}"))
    }
    "create a model with full JSON payload" in {
      modifyModel[SuccessResponse](Post, "test/model_json_full", StatusCodes.OK,
        Some("{'latestVersion': '1.0'}"))
    }
    "not create a model with a JSON payload attempting to unset a property" in {
      modifyModel[ErrorResponse](Post, "test/model_bad1", StatusCodes.BadRequest,
        Some("{'latestVersion': '@unset'}"))
    }
    "not create a model with a JSON payload with invalid property value" in {
      modifyModel[ErrorResponse](Post, "test/model_bad2", StatusCodes.BadRequest,
        Some("{'latestVersion': '-1.0'}")) // version tag must not start with "-"
    }
    "create a model with trailing slash" in {
      modifyModel[SuccessResponse](Post, "test/model2/", StatusCodes.OK)
    }
    "create a model with alnum characters and _-" in {
      modifyModel[SuccessResponse](Post, "test/test-MODEL_123", StatusCodes.OK)
    }
    "not create a duplicate model" in {
      modifyModel[ErrorResponse](Post, "test/model", StatusCodes.Conflict)
    }
    "create a model with same name in a different namespace" in {
      modifyModel[SuccessResponse](Post, "test2/model", StatusCodes.OK)
    }
    "not create a model in a non-existent namespace" in {
      modifyModel[ErrorResponse](Post, "doesnotexist/model", StatusCodes.NotFound)
    }
    "not create a model with name starting with a -" in {
      modifyModel[ErrorResponse](Post, "test/-model", StatusCodes.BadRequest)
    }
    "not create a model with name starting with a _" in {
      modifyModel[ErrorResponse](Post, "test/_model", StatusCodes.BadRequest)
    }
    "not create a model with name containing special chars" in {
      modifyModel[ErrorResponse](Post, "test/aaa-*", StatusCodes.BadRequest)
    }
    "not create a model with name containing non-ASCII chars" in {
      // "grząść", a typical Polish word
      modifyModel[ErrorResponse](Post, "test/grz%C4%85%C5%9B%C4%87", StatusCodes.BadRequest)
    }
    "not create a model with name longer than 100 characters" in {
      modifyModel[ErrorResponse](Post, "test/" + "0123456789".repeat(10) + "a", StatusCodes.BadRequest)
    }
    "create a model with name 100 characters long" in {
      modifyModel[SuccessResponse](Post, "test/" + "0123456789".repeat(10), StatusCodes.OK)
    }
    "create a model with name 1 character long" in {
      modifyModel[SuccessResponse](Post, "test/a", StatusCodes.OK)
    }
    "create a model with name 1 character long (digit)" in {
      modifyModel[SuccessResponse](Post, "test/1", StatusCodes.OK)
    }
  }

  "namespace endpoint" should {
    "list models in namespace" in {
      Get("/m/test") ~> nsRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        val response = responseAs[NamespaceClientModel]
        response shouldBe a [NamespaceClientModel]
        val models = response.models.value
        models.totalCount should be (8)
        models.items.toArray.length should be (8)
        models.items should contain (ModelClientModel("model", "test", None, None, None))
        models.items should contain (ModelClientModel("model2", "test", None, None, None))
        models.items should contain (ModelClientModel("model_json_full", "test", None, Some("1.0"), None))
      }
    }
  }

  "model endpoint" should {
    // Retrieving one model
    "retrieve one model" in {
      Get("/m/test/model") ~> modelRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        val response = responseAs[ModelClientModel]
        response shouldBe a [ModelClientModel]
        response.model should be ("model")
        response.namespace should be ("test")
        val versions = response.versions.value
        versions.totalCount should be (0)
        versions.items.toArray.length should be (0)
      }
    }
    "retrieve model with latest version set on POST" in {
      Get("/m/test/model_json_full") ~> modelRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        val response = responseAs[ModelClientModel]
        response.model should be ("model_json_full")
        response.latestVersion.value should be ("1.0")
      }
    }
    "retrieve one model with trailing slash" in {
      Get("/m/test/model2/") ~> modelRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        val response = responseAs[ModelClientModel]
        response shouldBe a [ModelClientModel]
      }
    }
    "not retrieve a non-existent model" in {
      Get("/m/test/doesnotexist") ~> modelRoute ~> check {
        status should be (StatusCodes.NotFound)
      }
    }
    "not retrieve a model in invalid namespace" in {
      Get("/t/model") ~> modelRoute ~> check {
        status should be (StatusCodes.NotFound)
      }
    }

    // Patching models
    "set a property on a model" in {
      modifyModel[SuccessResponse](Patch, "test/model_json_empty", StatusCodes.OK, Some("{'latestVersion': '2.0'}"))
    }
    "retrieve model with field set in PATCH" in {
      Get("/m/test/model_json_empty") ~> modelRoute ~> check {
        val response = responseAs[ModelClientModel]
        response.latestVersion.value should be ("2.0")
      }
    }
    "unset a property on a model" in {
      modifyModel[SuccessResponse](Patch, "test/model_json_full", StatusCodes.OK, Some("{'latestVersion': '@unset'}"))
    }
    "retrieve model with field unset in PATCH" in {
      Get("/m/test/model_json_full") ~> modelRoute ~> check {
        val response = responseAs[ModelClientModel]
        response.latestVersion should be (None)
      }
    }
    "return an error on empty patch body" in {
      modifyModel[ErrorResponse](Patch, "test/model", StatusCodes.BadRequest)
    }
    "ignore empty update JSON" in {
      val response = modifyModel[SuccessResponse](Patch, "test/model", StatusCodes.OK, Some("{}"))
      response.message should include ("No updates requested")
    }
    "set a property on a model – no update needed" in {
      val response = modifyModel[SuccessResponse](Patch, "test/model_json_empty", StatusCodes.OK,
        Some("{'latestVersion': '2.0'}"))
      response.message should include ("no update needed")
    }
    "not update a non-existent model" in {
      val response = modifyModel[ErrorResponse](Patch, "test/doesnotexist", StatusCodes.NotFound,
        Some("{'latestVersion': '1.0'}"))
      response.error should include ("Could not find model")
      response.error should include ("test/doesnotexist")
    }

    // Deleting models
    "not delete a model without the 'force' parameter" in {
      Delete("/m/test/test-MODEL_123") ~> modelRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val response = responseAs[ErrorResponse]
        response.error should include ("To really perform this action")
        response.error should include ("'force'")
      }
    }
    "not delete a model with the 'force' parameter not set to 1" in {
      Delete("/m/test/test-MODEL_123?force=true") ~> modelRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val response = responseAs[ErrorResponse]
        response.error should include ("To really perform this action")
        response.error should include ("'force'")
      }
    }
    "delete a model" in {
      Delete("/m/test/test-MODEL_123?force=1") ~> modelRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        responseAs[SuccessResponse] shouldBe a [SuccessResponse]
      }
    }
    "delete a model with trailing slash" in {
      Delete("/m/test/" + "0123456789".repeat(10) + "/?force=1") ~> modelRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        responseAs[SuccessResponse] shouldBe a [SuccessResponse]
      }
    }
    "not delete a non-existent model" in {
      Delete("/m/test/doesnotexist?force=1") ~> modelRoute ~> check {
        status should be (StatusCodes.NotFound)
      }
    }
    "not delete a model in non-existent namespace" in {
      Delete("/m/doesnotexist/model?force=1") ~> modelRoute ~> check {
        status should be (StatusCodes.NotFound)
      }
    }
    "not retrieve a deleted model" in {
      Get("/m/test/test-MODEL_123?force=1") ~> modelRoute ~> check {
        status should be (StatusCodes.NotFound)
      }
    }
  }
