package eu.assistiot.semantic_repo.core.test.rest

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.*
import akka.http.scaladsl.server.*
import akka.http.scaladsl.testkit.RouteTestTimeout
import akka.http.scaladsl.unmarshalling.{FromResponseUnmarshaller, Unmarshal}
import eu.assistiot.semantic_repo.core.{AppConfig, ControllerContainer, Guardian}
import eu.assistiot.semantic_repo.core.datamodel.*
import eu.assistiot.semantic_repo.core.rest.json.*
import eu.assistiot.semantic_repo.core.rest.resources.{ContentResource, ModelResource, ModelVersionResource, NamespaceResource, WebhookResource}
import eu.assistiot.semantic_repo.core.test.ApiSpec
import org.scalatest.DoNotDiscover
import org.scalatest.Inspectors
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.Futures.{interval, timeout}
import org.scalatest.concurrent.ScalaFutures
import spray.json.*

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.Future
import scala.reflect.ClassTag

@DoNotDiscover
class WebhookSpec extends ApiSpec, ScalaFutures, Inspectors:
  implicit val routeTimeout: RouteTestTimeout = RouteTestTimeout(5.seconds)

  val receivedHooks: mutable.Buffer[(String, JsValue)] = mutable.Buffer()

  def receiveWebhook(request: HttpRequest): Future[HttpResponse] =
    receivedHooks.append((
      request.uri.toString(),
      Unmarshal(request.entity).to[JsValue].futureValue,
    ))
    Future { HttpResponse() }

  override protected def getGuardian = Guardian(Some(receiveWebhook))
  val webhookRes = new WebhookResource(controllers.webhook)
  val nsRoute = Route.seal(NamespaceResource(controllers.webhook).route)
  val modelRoute = Route.seal(ModelResource(controllers.webhook).route)
  val mvRoute = Route.seal(ModelVersionResource(controllers.webhook).route)
  val contentRoute = Route.seal(ContentResource(controllers.webhook).route)

  def postWebhook[T: FromResponseUnmarshaller: ClassTag](statusCode: StatusCode, payload: Option[JsValue]): T =
    val request = payload match
      case None => Post(s"/webhook")
      case Some(p) => Post(
        s"/webhook",
        HttpEntity(ContentTypes.`application/json`, p.compactPrint)
      )
    request ~> Route.seal(webhookRes.route) ~> check {
      status should be (statusCode)
      contentType should be (ContentTypes.`application/json`)
      responseAs[T] shouldBe a [T]
      responseAs[T]
    }

  "webhook endpoint" when {
    "creating new webhooks" should {
      "accept a webhook with no context" in {
        postWebhook[WebhookCreatedInfo](StatusCodes.OK, Some(JsObject(
          "action" -> JsString("content_upload"),
          "callback" -> JsString("https://example.org/test"),
        )))
      }

      "accept a webhook with empty context" in {
        postWebhook[WebhookCreatedInfo](StatusCodes.OK, Some(JsObject(
          "action" -> JsString("content_upload"),
          "context" -> JsObject(),
          "callback" -> JsString("https://example.org/test"),
        )))
      }

      "accept a webhook with partial context" in {
        postWebhook[WebhookCreatedInfo](StatusCodes.OK, Some(JsObject(
          "action" -> JsString("content_upload"),
          "context" -> JsObject(
            "namespace" -> JsString("hooks"),
          ),
          "callback" -> JsString("https://example.org/test"),
        )))
      }

      "accept a webhook with full context" in {
        postWebhook[WebhookCreatedInfo](StatusCodes.OK, Some(JsObject(
          "action" -> JsString("content_upload"),
          "context" -> JsObject(
            "namespace" -> JsString("hooks"),
            "model" -> JsString("model"),
            "version" -> JsString("1"),
          ),
          "callback" -> JsString("https://example.org/test"),
        )))
      }

      "not accept a webhook with malformed body" in {
        val response = postWebhook[ErrorResponse](StatusCodes.BadRequest, Some(JsObject(
          "aa" -> JsNull,
        )))
        response.error should include ("malformed")
      }

      "not accept a webhook with no body" in {
        val response = postWebhook[ErrorResponse](StatusCodes.BadRequest, None)
        response.error should include ("malformed")
      }

      "not accept a webhook with unregistered action" in {
        val response = postWebhook[ErrorResponse](StatusCodes.BadRequest, Some(JsObject(
          "action" -> JsString("dance_commencing"),
          "callback" -> JsString("https://example.org/test"),
        )))
        response.error should include ("Unsupported action")
      }

      "not accept a webhook with too long callback URI" in {
        val response = postWebhook[ErrorResponse](StatusCodes.BadRequest, Some(JsObject(
          "action" -> JsString("content_upload"),
          "callback" -> JsString("https://example.org/test".repeat(100)),
        )))
        response.error should include ("URI is too long")
      }

      "not accept a webhook with invalid callback URI scheme" in {
        val response = postWebhook[ErrorResponse](StatusCodes.BadRequest, Some(JsObject(
          "action" -> JsString("content_upload"),
          "callback" -> JsString("ftp://example.org/test"),
        )))
        response.error should include ("Invalid callback scheme")
      }

      "not accept a webhook with missing callback URI scheme" in {
        val response = postWebhook[ErrorResponse](StatusCodes.BadRequest, Some(JsObject(
          "action" -> JsString("content_upload"),
          "callback" -> JsString("example.org/test"),
        )))
        response.error should include ("Callback URI must be absolute")
      }

      "not accept a webhook with invalid namespace context" in {
        val response = postWebhook[ErrorResponse](StatusCodes.BadRequest, Some(JsObject(
          "action" -> JsString("content_upload"),
          "context" -> JsObject(
            "namespace" -> JsString("t&*($#%ąę"),
          ),
          "callback" -> JsString("https://example.org/test"),
        )))
        response.error should include ("Namespace names must match regex")
      }

      "not accept a webhook with invalid model context" in {
        val response = postWebhook[ErrorResponse](StatusCodes.BadRequest, Some(JsObject(
          "action" -> JsString("content_upload"),
          "context" -> JsObject(
            "namespace" -> JsString("test"),
            "model" -> JsString("t&*($#%ąę"),
          ),
          "callback" -> JsString("https://example.org/test"),
        )))
        response.error should include ("Model names must match regex")
      }

      "not accept a webhook with model context, but no namespace context" in {
        val response = postWebhook[ErrorResponse](StatusCodes.BadRequest, Some(JsObject(
          "action" -> JsString("content_upload"),
          "context" -> JsObject(
            "model" -> JsString("test"),
          ),
          "callback" -> JsString("https://example.org/test"),
        )))
        response.error should include ("Context with a model must also have a namespace")
      }

      "not accept a webhook with invalid version context" in {
        val response = postWebhook[ErrorResponse](StatusCodes.BadRequest, Some(JsObject(
          "action" -> JsString("content_upload"),
          "context" -> JsObject(
            "namespace" -> JsString("test"),
            "model" -> JsString("test"),
            "version" -> JsString("")
          ),
          "callback" -> JsString("https://example.org/test"),
        )))
        response.error should include ("Version tags must match regex")
      }

      "not accept a webhook with version context, but no model context" in {
        val response = postWebhook[ErrorResponse](StatusCodes.BadRequest, Some(JsObject(
          "action" -> JsString("content_upload"),
          "context" -> JsObject(
            "version" -> JsString("test"),
          ),
          "callback" -> JsString("https://example.org/test"),
        )))
        response.error should include ("Context with a version must also have a model")
      }

      "accept 20 more webhooks" in {
        for _ <- 1 to 20 do
          postWebhook[WebhookCreatedInfo](StatusCodes.OK, Some(JsObject(
            "action" -> JsString("content_upload"),
            "callback" -> JsString("https://example.org/test"),
            "context" -> JsObject(
              "namespace" -> JsString("hooks"),
            ),
          )))
      }
    }

    "returning a single webhook" should {
      for whType <- MongoModel.WebhookAction.values do
        f"return an existing webhook (${whType.key})" in {
          // Create a new hook
          val postResponse = postWebhook[WebhookCreatedInfo](StatusCodes.OK, Some(JsObject(
            "action" -> JsString(whType.key),
            "callback" -> JsString("https://example.org/test"),
            "context" -> JsObject(
              "namespace" -> JsString("testo"),
              "model" -> JsString("modelo"),
              "version" -> JsString("versiono")
            ),
          )))

          Get("/webhook/" + postResponse.handle) ~> webhookRes.route ~> check {
            status should be (StatusCodes.OK)
            contentType should be (ContentTypes.`application/json`)
            val response: WebhookClientModel = responseAs[WebhookClientModel]
            response.id should be (postResponse.handle)
            response.action should be (whType.key)
            response.callback should be ("https://example.org/test")
            response.context.namespace should be (Some("testo"))
            response.context.model should be (Some("modelo"))
            response.context.version should be (Some("versiono"))
          }
        }

      "not return a webhook with ID in a wrong format" in {
        Get("/webhook/123") ~> Route.seal(webhookRes.route) ~> check {
          status should be (StatusCodes.NotFound)
        }
      }

      "not return a non-existent webhook" in {
        // This is a valid ID... but it should not exist
        Get("/webhook/012345678901234567890123") ~> webhookRes.route ~> check {
          status should be (StatusCodes.NotFound)
          contentType should be (ContentTypes.`application/json`)
          val response: ErrorResponse = responseAs[ErrorResponse]
          response.error should include ("could not be found")
          response.error should include ("012345678901234567890123")
        }
      }
    }

    "deleting a webhook" should {
      "delete a webhook" in {
        // Create a new hook
        val postResponse = postWebhook[WebhookCreatedInfo](StatusCodes.OK, Some(JsObject(
          "action" -> JsString("content_upload"),
          "callback" -> JsString("https://example.org/test"),
        )))

        Delete("/webhook/" + postResponse.handle + "?force=1") ~> webhookRes.route ~> check {
          status should be (StatusCodes.OK)
          contentType should be (ContentTypes.`application/json`)
          val response: SuccessResponse = responseAs[SuccessResponse]
          response.message should include ("Deleted webhook with ID")
          response.message should include (postResponse.handle)
        }

        Get("/webhook/" + postResponse.handle) ~> Route.seal(webhookRes.route) ~> check {
          status should be (StatusCodes.NotFound)
        }
      }

      "not delete a non-existent webhook" in {
        Delete("/webhook/012345678901234567890123?force=1") ~> webhookRes.route ~> check {
          status should be (StatusCodes.NotFound)
          contentType should be (ContentTypes.`application/json`)
          val response: ErrorResponse = responseAs[ErrorResponse]
          response.error should include ("could not be found")
          response.error should include ("012345678901234567890123")
        }
      }

      "not delete a webhook without the force parameter" in {
        Delete("/webhook/012345678901234567890123") ~> webhookRes.route ~> check {
          status should be (StatusCodes.BadRequest)
          contentType should be (ContentTypes.`application/json`)
          val response: ErrorResponse = responseAs[ErrorResponse]
          response.error should include ("To really perform this action, the 'force' parameter must be set to '1'")
        }
      }
    }

    "when browsing webhooks" should {
      "list the webhooks with no parameters" in {
        Get("/webhook") ~> webhookRes.route ~> check {
          status should be (StatusCodes.OK)
          contentType should be (ContentTypes.`application/json`)
          val response: WebhookListClientModel = responseAs[WebhookListClientModel]
          val set = response.webhooks
          set.inViewCount should be (20)
          set.pageSize should be (20)
          set.totalCount should be > 20
          set.page should be (1)
          set.items.size should be (20)
        }
      }

      "list the second page of webhooks" in {
        Get("/webhook?page=2") ~> webhookRes.route ~> check {
          status should be (StatusCodes.OK)
          contentType should be (ContentTypes.`application/json`)
          val response: WebhookListClientModel = responseAs[WebhookListClientModel]
          val set = response.webhooks
          set.inViewCount should be > 0
          set.pageSize should be (20)
          set.totalCount should be > 20
          set.page should be (2)
          set.items.size should be (set.inViewCount)
        }
      }

      "list a page of 50 webhooks" in {
        Get("/webhook?page_size=50") ~> webhookRes.route ~> check {
          status should be (StatusCodes.OK)
          contentType should be (ContentTypes.`application/json`)
          val response: WebhookListClientModel = responseAs[WebhookListClientModel]
          val set = response.webhooks
          set.inViewCount should be <= 50
          set.pageSize should be (50)
          set.totalCount should be > 20
          set.page should be (1)
          set.items.size should be (set.inViewCount)
          atLeast(1, set.items.map(_.context.namespace.isDefined)) should be (true)
        }
      }

      "filter by non-existent action" in {
        Get("/webhook?f.action=dance_commencing") ~> webhookRes.route ~> check {
          status should be (StatusCodes.OK)
          contentType should be (ContentTypes.`application/json`)
          val response: WebhookListClientModel = responseAs[WebhookListClientModel]
          val set = response.webhooks
          set.inViewCount should be (0)
          set.pageSize should be (20)
          set.totalCount should be (0)
          set.page should be (1)
          set.items.size should be (0)
        }
      }

      "filter by content_upload action" in {
        Get("/webhook?f.action=content_upload") ~> webhookRes.route ~> check {
          status should be (StatusCodes.OK)
          contentType should be (ContentTypes.`application/json`)
          val response: WebhookListClientModel = responseAs[WebhookListClientModel]
          val set = response.webhooks
          set.inViewCount should be (20)
          set.pageSize should be (20)
          set.totalCount should be > 20
          set.page should be (1)
          set.items.size should be (20)
        }
      }
    }

    val callbacks = Seq(
      ("https://example.org/test1", JsObject()),
      ("http://localhost/hook2", JsObject()),
      ("https://example.org/namespaceHook", JsObject(
        "namespace" -> JsString("hook_ns"),
      )),
      ("https://example.org/modelHook", JsObject(
        "namespace" -> JsString("hook_ns"),
        "model" -> JsString("model"),
      )),
      ("https://example.org/versionHook", JsObject(
        "namespace" -> JsString("hook_ns"),
        "model" -> JsString("model"),
        "version" -> JsString("1"),
      )),
    )

    "when executing webhooks" should {
      "execute content_upload hooks" in {
        receivedHooks.clear()

        // Prepare the namespace
        Post("/m/hook_ns") ~> nsRoute ~> check {
          status should (be (StatusCodes.OK) or be (StatusCodes.Conflict))
        }
        Post("/m/hook_ns/model") ~> modelRoute ~> check {
          status should (be (StatusCodes.OK) or be (StatusCodes.Conflict))
        }
        Post("/m/hook_ns/model/1") ~> mvRoute ~> check {
          status should (be (StatusCodes.OK) or be (StatusCodes.Conflict))
        }

        val expectedHooks = for (callback, context) <- callbacks yield
          val response = postWebhook[WebhookCreatedInfo](StatusCodes.OK, Some(JsObject(
            "action" -> JsString("content_upload"),
            "callback" -> JsString(callback),
            "context" -> context,
          )))
          (callback, response.handle)

        val notExpectedHookInfo = postWebhook[WebhookCreatedInfo](StatusCodes.OK, Some(JsObject(
          "action" -> JsString("content_upload"),
          "callback" -> JsString("http://localhost/dontRun"),
          "context" -> JsObject(
            "namespace" -> JsString("other-ns")
          ),
        )))

        // upload some content (overwrite)
        Post(
          "/m/hook_ns/model/1/content?format=application/json&overwrite=1",
          ContentSpec.createEntity("/small.json", ContentTypes.`application/json`),
        ) ~> contentRoute ~> check {
          status should be (StatusCodes.OK)
        }

        eventually(timeout(10.seconds), interval(2.seconds)) {
          receivedHooks.size should be >= 5
          for (expectedCallback, expectedHandle) <- expectedHooks do
            forExactly(1, receivedHooks) { (callback, payload) =>
              callback should be (expectedCallback)
              payload shouldBe a [JsObject]
              val payloadO = payload.asInstanceOf[JsObject]
              payloadO.fields("action") should be (JsString("content_upload"))
              payloadO.fields("hookId") should be (JsString(expectedHandle))
              payloadO.fields("timestamp").asInstanceOf[JsString].value.length should be (19)

              val context = payloadO.fields("context").asJsObject
              context.fields("namespace") should be (JsString("hook_ns"))
              context.fields("model") should be (JsString("model"))
              context.fields("version") should be (JsString("1"))

              val body = payloadO.fields("body").asJsObject
              body.fields("size") should be (JsNumber(2))
              body.fields("contentType") should be (JsString("application/json"))
              body.fields("md5") should be (JsString(ContentSpec.smallJsonContentMD5))
              body.fields("format") should be (JsString("application/json"))
              body.fields("overwrite") should be (JsBoolean(false))
            }
        }

        forAll(receivedHooks) { (callback, payload) =>
          callback should not be "http://localhost/dontRun"
          payload.asInstanceOf[JsObject].fields("hookId") should not be notExpectedHookInfo.handle
        }
      }

      "execute content_delete hooks" in {
        receivedHooks.clear()

        val expectedHooks = for (callback, context) <- callbacks yield
          val response = postWebhook[WebhookCreatedInfo](StatusCodes.OK, Some(JsObject(
            "action" -> JsString("content_delete"),
            "callback" -> JsString(callback),
            "context" -> context,
          )))
          (callback, response.handle)

        val notExpectedHookInfo = postWebhook[WebhookCreatedInfo](StatusCodes.OK, Some(JsObject(
          "action" -> JsString("content_delete"),
          "callback" -> JsString("http://localhost/dontRun"),
          "context" -> JsObject(
            "namespace" -> JsString("other-ns")
          ),
        )))

        // Delete content from the previous test
        Delete(
          "/m/hook_ns/model/1/content?format=application/json&force=1",
        ) ~> contentRoute ~> check {
          status should be (StatusCodes.OK)
        }

        eventually(timeout(10.seconds), interval(2.seconds)) {
          receivedHooks.size should be >= 5
          for (expectedCallback, expectedHandle) <- expectedHooks do
            forExactly(1, receivedHooks) { (callback, payload) =>
              callback should be (expectedCallback)
              payload shouldBe a [JsObject]
              val payloadO = payload.asInstanceOf[JsObject]
              payloadO.fields("action") should be(JsString("content_delete"))
              payloadO.fields("hookId") should be(JsString(expectedHandle))
              payloadO.fields("timestamp").asInstanceOf[JsString].value.length should be(19)

              val context = payloadO.fields("context").asJsObject
              context.fields("namespace") should be(JsString("hook_ns"))
              context.fields("model") should be(JsString("model"))
              context.fields("version") should be(JsString("1"))

              val body = payloadO.fields("body").asJsObject
              body.fields("format") should be(JsString("application/json"))
            }
        }

        forAll(receivedHooks) { (callback, payload) =>
          callback should not be "http://localhost/dontRun"
          payload.asInstanceOf[JsObject].fields("hookId") should not be notExpectedHookInfo.handle
        }
      }

      "execute model_version_delete hooks" in {
        receivedHooks.clear()

        val expectedHooks = for (callback, context) <- callbacks yield
          val response = postWebhook[WebhookCreatedInfo](StatusCodes.OK, Some(JsObject(
            "action" -> JsString("model_version_delete"),
            "callback" -> JsString(callback),
            "context" -> context,
          )))
          (callback, response.handle)

        // Delete model version from the previous test
        Delete(
          "/m/hook_ns/model/1?force=1",
        ) ~> mvRoute ~> check {
          status should be(StatusCodes.OK)
        }

        eventually(timeout(10.seconds), interval(2.seconds)) {
          receivedHooks.size should be >= 5
          for (expectedCallback, expectedHandle) <- expectedHooks do
            forExactly(1, receivedHooks) { (callback, payload) =>
              callback should be(expectedCallback)
              payload shouldBe a[JsObject]
              val payloadO = payload.asInstanceOf[JsObject]
              payloadO.fields("action") should be(JsString("model_version_delete"))
              payloadO.fields("hookId") should be(JsString(expectedHandle))
              payloadO.fields("timestamp").asInstanceOf[JsString].value.length should be(19)

              val context = payloadO.fields("context").asJsObject
              context.fields("namespace") should be(JsString("hook_ns"))
              context.fields("model") should be(JsString("model"))
              context.fields("version") should be(JsString("1"))

              val body = payloadO.fields("body").asJsObject
              body.fields.size should be (0)
            }
        }
      }

      "execute model_delete hooks" in {
        receivedHooks.clear()

        val expectedHooks = for (callback, context) <- callbacks
          .filterNot(_._2.fields.keys.exists(_ == "version"))
        yield
          val response = postWebhook[WebhookCreatedInfo](StatusCodes.OK, Some(JsObject(
            "action" -> JsString("model_delete"),
            "callback" -> JsString(callback),
            "context" -> context,
          )))
          (callback, response.handle)

        // Delete model from the previous test
        Delete(
          "/m/hook_ns/model?force=1",
        ) ~> modelRoute ~> check {
          status should be(StatusCodes.OK)
        }

        eventually(timeout(10.seconds), interval(2.seconds)) {
          receivedHooks.size should be >= 4
          for (expectedCallback, expectedHandle) <- expectedHooks do
            forExactly(1, receivedHooks) { (callback, payload) =>
              callback should be(expectedCallback)
              payload shouldBe a[JsObject]
              val payloadO = payload.asInstanceOf[JsObject]
              payloadO.fields("action") should be(JsString("model_delete"))
              payloadO.fields("hookId") should be(JsString(expectedHandle))
              payloadO.fields("timestamp").asInstanceOf[JsString].value.length should be(19)

              val context = payloadO.fields("context").asJsObject
              context.fields("namespace") should be(JsString("hook_ns"))
              context.fields("model") should be(JsString("model"))
              context.fields.size should be (2)

              val body = payloadO.fields("body").asJsObject
              body.fields.size should be(0)
            }
        }
      }

      "execute namespace_delete hooks" in {
        receivedHooks.clear()

        val expectedHooks = for (callback, context) <- callbacks
          .filterNot(_._2.fields.keys.exists(_ == "model"))
        yield
          val response = postWebhook[WebhookCreatedInfo](StatusCodes.OK, Some(JsObject(
            "action" -> JsString("namespace_delete"),
            "callback" -> JsString(callback),
            "context" -> context,
          )))
          (callback, response.handle)

        // Delete namespace from the previous test
        Delete(
          "/m/hook_ns?force=1",
        ) ~> nsRoute ~> check {
          status should be(StatusCodes.OK)
        }

        eventually(timeout(10.seconds), interval(2.seconds)) {
          receivedHooks.size should be >= 3
          for (expectedCallback, expectedHandle) <- expectedHooks do
            forExactly(1, receivedHooks) { (callback, payload) =>
              callback should be(expectedCallback)
              payload shouldBe a[JsObject]
              val payloadO = payload.asInstanceOf[JsObject]
              payloadO.fields("action") should be(JsString("namespace_delete"))
              payloadO.fields("hookId") should be(JsString(expectedHandle))
              payloadO.fields("timestamp").asInstanceOf[JsString].value.length should be(19)

              val context = payloadO.fields("context").asJsObject
              context.fields("namespace") should be(JsString("hook_ns"))
              context.fields.size should be(1)

              val body = payloadO.fields("body").asJsObject
              body.fields.size should be(0)
            }
        }
      }
    }
  }
