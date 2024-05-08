package eu.assistiot.semantic_repo.core.test.rest

import akka.http.scaladsl.model.*
import akka.http.scaladsl.server.*
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.unmarshalling.FromResponseUnmarshaller
import eu.assistiot.semantic_repo.core.buildinfo.BuildInfo
import eu.assistiot.semantic_repo.core.datamodel.*
import eu.assistiot.semantic_repo.core.rest.json
import eu.assistiot.semantic_repo.core.rest.resources.*
import eu.assistiot.semantic_repo.core.rest.json.*
import eu.assistiot.semantic_repo.core.test.ApiSpec
import org.scalatest.{DoNotDiscover, Inspectors}
import org.scalatest.concurrent.ScalaFutures

import java.io.File
import scala.reflect.ClassTag

object ContentSpec:
  def createEntity(testFile: String, contentType: ContentType, chunkSize: Int = -1) =
    val file = new File(getClass.getResource(testFile).toURI)
    Multipart.FormData.fromFile("content", contentType, file = file, chunkSize)

  // MD5 checksums of (in order): small.json, medium.json, medium.xml
  val smallJsonContentMD5 = "99914b932bd37a50b983c5e7c90ae93b"
  val mediumJsonContentMD5 = "6c11044173d94b2cb8bceed5c5fe9b22"
  val mediumXmlContentMD5 = "150b0c02d7a7ef04f8267b16795297df"

/**
 * Integration tests for the content endpoints.
 */
@DoNotDiscover
class ContentSpec extends ApiSpec, ScalaFutures, Inspectors:
  import ContentSpec.*

  val contentRoute = ContentResource(controllers.webhook).route
  val mvRoute = Route.seal(ModelVersionResource(controllers.webhook).route)

  "content endpoint" should {
    // Uploading
    "upload first content with format matching Content-Type" in {
      Post(
        "/m/test/model/1.0.0/content?format=application/json",
        createEntity("/medium.json", ContentTypes.`application/json`),
      ) ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        val response = responseAs[SuccessResponse]
        response.message should include ("application/json")
        response.message should include ("test/model/1.0.0")
        response.message should include (mediumJsonContentMD5)
        val warns = response.warnings.value
        // The default format should be changed
        exactly(1, warns) should include ("default format")
      }
    }

    "not upload content in the same format without the overwrite parameter" in {
      Post(
        "/m/test/model/1.0.0/content?format=application/json",
        createEntity("/small.json", ContentTypes.`application/json`),
      ) ~> contentRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val response = responseAs[ErrorResponse]
        response.error should include ("application/json")
        response.error should include ("already exists for this model version")
      }
    }

    "upload same content (with trailing slash), overwrite=1" in {
      Post(
        "/m/test/model/1.0.0/content/?format=application/json&overwrite=1",
        createEntity("/small.json", ContentTypes.`application/json`),
      ) ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        val response = responseAs[SuccessResponse]
        response.message should include ("application/json")
        response.message should include ("test/model/1.0.0")
        response.message should include (smallJsonContentMD5)
        val warns = response.warnings.value
        exactly(1, warns) should include ("Overwrote an earlier version")
      }
    }

    "upload second content with format that is an invalid Media Type" in {
      Post(
        "/m/test/model/1.0.0/content?format=testFormat",
        createEntity("/medium.xml", ContentTypes.`text/xml(UTF-8)`),
      ) ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        val response = responseAs[SuccessResponse]
        response.message should include (mediumXmlContentMD5)
        val warns = response.warnings.value
        // The default format should NOT be changed
        all(warns) shouldNot include ("default format")
        exactly(1, warns) should include ("not a valid Media Type")
      }
    }

    "upload third content with format that does not match Content-Type" in {
      Post(
        "/m/test/model/1.0.0/content?format=application/ld%2bjson", // JSON-LD
        createEntity("/small.json", ContentTypes.`application/json`),
      ) ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        val response = responseAs[SuccessResponse]
        val warns = response.warnings.value
        // The default format should NOT be changed
        all(warns) shouldNot include ("default format")
        exactly(1, warns) should include ("does not match the one in the body")
      }
    }

    "upload multipart content" in {
      Post(
        "/m/test/model/1.0.0/content?format=multipart",
        createEntity("/medium.json", ContentTypes.`application/json`, 20),
      ) ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
        val response = responseAs[SuccessResponse]
        response.message should include ("multipart")
        response.message should include ("test/model/1.0.0")
        response.message should include (mediumJsonContentMD5)
      }
    }

    "not upload content with missing format parameter" in {
      Post(
        "/m/test/model/1.0.0/content",
        createEntity("/small.json", ContentTypes.`application/json`),
      ) ~> contentRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val response = responseAs[ErrorResponse]
        response.error should include ("must be given explicitly")
      }
    }

    "upload content to a different model version" in {
      Post(
        "/m/test/model/1.1.0/content?format=application/json",
        createEntity("/small.json", ContentTypes.`application/json`),
      ) ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }

    "upload content to a yet another model version" in {
      Post(
        "/m/test/model/1/content?format=application/json",
        createEntity("/small.json", ContentTypes.`application/json`),
      ) ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }

    "upload content to a different model" in {
      Post(
        "/m/test/model2/1.0.0/content?format=application/json",
        createEntity("/small.json", ContentTypes.`application/json`),
      ) ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }

    "upload content to a different namespace" in {
      Post(
        "/m/test2/model/1.0.0/content?format=application/json",
        createEntity("/small.json", ContentTypes.`application/json`),
      ) ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }

    "upload second content to a different namespace" in {
      Post(
        "/m/test2/model/1.0.0/content?format=xml",
        createEntity("/medium.xml", ContentTypes.`text/xml(UTF-8)`),
      ) ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }

    val formatCases = Map(
      "containing illegal characters" -> "aa*",
      "that is too long" -> ("0123456789".repeat(10) + "a"),
      "starting with -" -> "-a",
      "starting with +" -> "+a",
      "starting with ." -> ".a",
      "starting with /" -> "/a",
      "starting with _" -> "_a",
    )
    for ((k, v) <- formatCases)
      s"not upload content with format parameter $k" in {
        Post(
          s"/m/test/model/1.0.0/content?format=$v",
          createEntity("/small.json", ContentTypes.`application/json`),
        ) ~> contentRoute ~> check {
          status should be (StatusCodes.BadRequest)
          val response = responseAs[ErrorResponse]
          response.error should include ("must match regex")
        }
      }

    "not upload content to nonexistent model" in {
      Post(
        "/m/test/doesnotexist/1.0.0/content?format=application/test",
        createEntity("/small.json", ContentTypes.`application/json`),
      ) ~> contentRoute ~> check {
        status should be (StatusCodes.NotFound)
        responseAs[ErrorResponse] shouldBe an [ErrorResponse]
      }
    }

    "not upload content to the 'latest' tag" in {
      Post(
        "/m/test/model/latest/content?format=application/test",
        createEntity("/small.json", ContentTypes.`application/json`),
      ) ~> contentRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val response = responseAs[ErrorResponse]
        response.error should include ("cannot be used for writes")
      }
    }
  }

  "model version endpoint" should {
    "set default format to an non-existent format of a model" in {
      Patch("/m/test/model2/1.0.0",
        HttpEntity(ContentTypes.`application/json`, "{\"defaultFormat\": \"doesnotexist\"}")
      ) ~> mvRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }

    "set default format to a different, exisitng format of a model" in {
      Patch("/m/test2/model/1.0.0",
        HttpEntity(ContentTypes.`application/json`, "{\"defaultFormat\": \"xml\"}")
      ) ~> mvRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }

    "unset the default format" in {
      Patch("/m/test/model/1",
        HttpEntity(ContentTypes.`application/json`, "{\"defaultFormat\": \"@unset\"}")
      ) ~> mvRoute ~> check {
        status should be (StatusCodes.OK)
      }
    }

    "list model version's formats" in {
      Get("/m/test/model/1.0.0") ~> mvRoute ~> check {
        status should be (StatusCodes.OK)
        val response = responseAs[ModelVersionClientModel]
        response.defaultFormat.value should be ("application/json")
        val formats: Map[String, StoredFileClientModel] = response.formats.value
        formats.size should be (4)

        val jsonFile = formats.get("application/json").value
        jsonFile.size should be (2)
        jsonFile.md5 should be (smallJsonContentMD5)
        jsonFile.contentType should be ("application/json")

        val xmlFile = formats.get("testFormat").value
        xmlFile.md5 should be (mediumXmlContentMD5)
        xmlFile.contentType should be (ContentTypes.`text/xml(UTF-8)`.toString)
      }
    }
  }

  "content endpoint" should {
    // retrieval
    "return content in an explicitly specified format" in {
      Get("/m/test/model/1.0.0/content?format=testFormat") ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`text/xml(UTF-8)`)
      }
    }

    "return content in an explicitly specified format with trailing slash" in {
      Get("/m/test/model/1.0.0/content/?format=testFormat") ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`text/xml(UTF-8)`)
      }
    }

    "not return content in a non-existent, explicitly specified format" in {
      Get("/m/test/model/1.0.0/content?format=doesnotexist") ~> contentRoute ~> check {
        status should be (StatusCodes.NotFound)
        responseAs[ErrorResponse].error should include ("The specified format")
      }
    }

    "return content in the default format" in {
      Get("/m/test/model/1.0.0/content/") ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
      }
    }

    "return content in the default format that was set manually" in {
      Get("/m/test2/model/1.0.0/content") ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`text/xml(UTF-8)`)
      }
    }

    "not return content for a broken default format pointer" in {
      Get("/m/test/model2/1.0.0/content") ~> contentRoute ~> check {
        status should be (StatusCodes.NotFound)
        responseAs[ErrorResponse].error should include ("Please update the defaultFormat field")
      }
    }

    "not return content for an unset default format pointer" in {
      Get("/m/test/model/1/content") ~> contentRoute ~> check {
        status should be (StatusCodes.NotFound)
        responseAs[ErrorResponse].error should include ("The 'format' parameter was not specified and the " +
          "default format for this model version is not set.")
      }
    }

    "not return content for a nonexistent model" in {
      Get("/m/test/doesnotexist/1.0.0/content/?format=testFormat") ~> contentRoute ~> check {
        status should be (StatusCodes.NotFound)
        responseAs[ErrorResponse].error should include ("Could not find model version")
      }
    }

    // standard endpoint, latest tag
    "return default format content via a valid 'latest' tag" in {
      Get("/m/test/model/latest/content") ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
      }
    }
    "not return content for a broken 'latest' tag" in {
      Get("/m/test/model2/latest/content/") ~> contentRoute ~> check {
        status should be (StatusCodes.NotFound)
        responseAs[ErrorResponse].error should include ("Please update the pointer")
      }
    }
    "not return content for an unset 'latest' tag" in {
      Get("/m/test2/model/latest/content/") ~> contentRoute ~> check {
        status should be (StatusCodes.NotFound)
        responseAs[ErrorResponse].error should include ("pointer is not set")
      }
    }

    // shorthand endpoint
    "return content from shorthand endpoint with explicit, non-slash format" in {
      Get("/c/test/model/1.0.0/testFormat") ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`text/xml(UTF-8)`)
      }
    }

    "return content from shorthand endpoint with explicit format with a slash" in {
      Get("/c/test/model/1.0.0/application/json") ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
      }
    }

    "return content from shorthand endpoint with explicit format with a plus" in {
      Get("/c/test/model/1.0.0/application/ld%2bjson") ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
      }
    }

    "return content from shorthand endpoint with default format" in {
      Get("/c/test/model/1.0.0") ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
      }
    }

    "return content from shorthand endpoint with default format (trailing slash)" in {
      Get("/c/test/model/1.0.0") ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
      }
    }

    "not return content from shorthand endpoint for a nonexistent model" in {
      Get("/c/test/doesnotexist/1.0.0/content/testFormat") ~> contentRoute ~> check {
        status should be (StatusCodes.NotFound)
        responseAs[ErrorResponse].error should include ("Could not find model version")
      }
    }
    
    // shorthand endpoint, latest tag
    "return latest content from shorthand endpoint with explicit format with a slash" in {
      Get("/c/test/model/latest/application/json") ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
      }
    }
    "return latest content from shorthand endpoint with default format" in {
      Get("/c/test/model/latest") ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
      }
    }
    "return latest content from super-shorthand endpoint (GET /c/{ns}/{model})" in {
      Get("/c/test/model") ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        contentType should be (ContentTypes.`application/json`)
      }
    }

    // deletion
    "not delete content without the 'force' parameter" in {
      Delete("/m/test/model/1.0.0/content/?format=testFormat") ~> contentRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val response = responseAs[ErrorResponse]
        response.error should include ("To really perform this action")
        response.error should include ("'force'")
      }
    }
    "not delete content with the 'force' parameter not set to 1" in {
      Delete("/m/test/model/1.0.0/content/?format=testFormat&force=true") ~> contentRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val response = responseAs[ErrorResponse]
        response.error should include ("To really perform this action")
        response.error should include ("'force'")
      }
    }
    "not delete content without explicitly specified format" in {
      Delete("/m/test/model/1.0.0/content/?force=1") ~> contentRoute ~> check {
        status should be (StatusCodes.BadRequest)
        responseAs[ErrorResponse].error should include ("Format must be given explicitly")
      }
    }

    "not delete content in nonexistent model" in {
      Delete("/m/test/doesnotexist/1.0.0/content/?format=testFormat&force=1") ~> contentRoute ~> check {
        status should be (StatusCodes.NotFound)
        responseAs[ErrorResponse] shouldBe an [ErrorResponse]
      }
    }

    "not delete content in nonexistent format" in {
      Delete("/m/test/model/1.0.0/content/?format=doesNotExist&force=1") ~> contentRoute ~> check {
        status should be (StatusCodes.NotFound)
        responseAs[ErrorResponse] shouldBe an [ErrorResponse]
      }
    }

    "not delete content via the 'latest' tag" in {
      Delete("/m/test/model/latest/content/?format=application/json&force=1") ~> contentRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val response = responseAs[ErrorResponse]
        response.error should include ("cannot be used for writes")
      }
    }

    "delete content (non-default format)" in {
      Delete("/m/test/model/1.0.0/content/?format=testFormat&force=1") ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        responseAs[SuccessResponse] shouldBe a [SuccessResponse]
      }
    }

    "not return deleted content" in {
      Get("/m/test/model/1.0.0/content/?format=testFormat") ~> contentRoute ~> check {
        status should be (StatusCodes.NotFound)
        responseAs[ErrorResponse].error should include ("The specified format")
      }
    }

    "delete content (default format)" in {
      Delete("/m/test/model/1.0.0/content?format=application/json&force=1") ~> contentRoute ~> check {
        status should be (StatusCodes.OK)
        responseAs[SuccessResponse] shouldBe a [SuccessResponse]
      }
    }

    "not return deleted content in the default format" in {
      Get("/m/test/model/1.0.0/content") ~> contentRoute ~> check {
        status should be (StatusCodes.NotFound)
        responseAs[ErrorResponse].error should include ("a file in this format could not be found")
      }
    }
  }

  "model version endpoint" should {
    "should not list deleted formats" in {
      Get("/m/test/model/1.0.0") ~> mvRoute ~> check {
        status should be (StatusCodes.OK)
        val response = responseAs[ModelVersionClientModel]
        val formats: Map[String, StoredFileClientModel] = response.formats.value
        formats.size should be (2)
      }
    }
  }
