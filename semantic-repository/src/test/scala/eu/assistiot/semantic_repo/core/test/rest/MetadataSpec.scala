package eu.assistiot.semantic_repo.core.test.rest

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCode, StatusCodes}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.unmarshalling.FromResponseUnmarshaller
import eu.assistiot.semantic_repo.core.AppConfig
import eu.assistiot.semantic_repo.core.datamodel.*
import eu.assistiot.semantic_repo.core.rest.resources.*
import eu.assistiot.semantic_repo.core.rest.json.*
import eu.assistiot.semantic_repo.core.test.ApiSpec
import org.scalatest.{EitherValues, Inspectors}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.DoNotDiscover

import scala.reflect.ClassTag

@DoNotDiscover
class MetadataSpec extends ApiSpec, ScalaFutures, Inspectors, EitherValues:
  val nsRes = NamespaceResource(controllers.webhook)
  val modelRes = ModelResource(controllers.webhook)
  val mvRes = ModelVersionResource(controllers.webhook)

  val endpoints = Seq(
    Endpoint[NamespaceClientModel, RootInfoClientModel](
      "namespace",
      "/m/",
      nsRes,
      nsRes
    ),
    Endpoint[ModelClientModel, NamespaceClientModel](
      "model",
      "/m/metadata/",
      modelRes,
      nsRes
    ),
    Endpoint[ModelVersionClientModel, ModelClientModel](
      "model version",
      "/m/metadata/metadata/",
      mvRes,
      modelRes
    ),
  )

  def modifyEntity[T : FromResponseUnmarshaller : ClassTag]
  (method: RequestBuilder, path: String, statusCode: StatusCode, payload: Option[String] = None)
  (implicit resource: Resource): T =
    val request = payload match
      case None => method(path)
      case Some(p) => method(
        path,
        HttpEntity(ContentTypes.`application/json`, p.replace('\'', '"'))
      )
    request ~> Route.seal(resource.route) ~> check {
      status should be (statusCode)
      contentType should be (ContentTypes.`application/json`)
      responseAs[T] shouldBe a [T]
      responseAs[T]
    }

  // Set up parent entities first
  "namespace & model endpoints" should {
    "create a test namespace" in {
      implicit val resource: Resource = nsRes
      modifyEntity[SuccessResponse](Post, "/m/metadata", StatusCodes.OK, Some("{}"))
    }
    "create a test model" in {
      implicit val resource: Resource = modelRes
      modifyEntity[SuccessResponse](Post, "/m/metadata/metadata", StatusCodes.OK, Some("{}"))
    }
  }

  // Do the actual testing
  for endpoint <- endpoints do
    endpoint.test()


  case class Endpoint[+TEnt <: HasMetadata : FromResponseUnmarshaller : ClassTag,
    +TParent <: HasSet[TEnt] : FromResponseUnmarshaller : ClassTag]
  (key: String, pathPrefix: String, resource: Resource, parentResource: Resource):
    def test(): Unit =
      implicit val rs: Resource = resource

      s"$key endpoint" should {
        "create entity with no metadata" in {
          modifyEntity[SuccessResponse](Post, s"${pathPrefix}meta_1", StatusCodes.OK, Some("{}"))
        }

        // Keys validation
        val invalidKeys = Seq("", "***", "0123456789".repeat(10) + "a")
        for ((mKey, ix) <- invalidKeys.zipWithIndex)
          s"not create an entity with invalid metadata key $ix" in {
            val response = modifyEntity[ErrorResponse](Post, s"${pathPrefix}meta_2_$ix",
              StatusCodes.BadRequest, Some(s"{'metadata': {'$mKey': 'value'}}"))
            response.error should include ("Invalid metadata key")
          }

        val maxProps = (1 to AppConfig.Limits.Metadata.maxProperties)
          .map(i => s"'k$i': 'v$i'")
          .reduce((a, b) => s"$a, $b")

        "create an entity with maximum number of metadata properties" in {
          modifyEntity[SuccessResponse](Post, s"${pathPrefix}meta_3", StatusCodes.OK,
            Some(s"{'metadata': {$maxProps}}"))
        }

        "not create an entity with too many metadata properties" in {
          val maxProps2 = maxProps + ", 'key': 'value'"
          val response = modifyEntity[ErrorResponse](Post, s"${pathPrefix}meta_4", StatusCodes.BadRequest,
            Some(s"{'metadata': {$maxProps2}}"))
          response.error should include ("Too many metadata keys")
        }

        // Value validation
        "not create an entity with metadata value @unset" in {
          val response = modifyEntity[ErrorResponse](Post, s"${pathPrefix}meta_5a", StatusCodes.BadRequest,
            Some("{'metadata': {'key': '@unset'}}"))
          response.error should include ("'metadata.key' cannot be unset during creation")
        }

        "not create an entity with numeric value" in {
          val response = modifyEntity[ErrorResponse](Post, s"${pathPrefix}meta_5b", StatusCodes.BadRequest,
            Some("{'metadata': {'key': 1}}"))
          // this is a schema validation error
          response.error should include ("malformed")
        }

        "not create an entity with null value" in {
          val response = modifyEntity[ErrorResponse](Post, s"${pathPrefix}meta_5c", StatusCodes.BadRequest,
            Some("{'metadata': {'key': null}}"))
          response.error should include ("malformed")
        }

        "create an entity with maximum length metadata value" in {
          val mValue = "a".repeat(AppConfig.Limits.Metadata.maxValueLength)
          modifyEntity[SuccessResponse](Post, s"${pathPrefix}meta_6", StatusCodes.OK,
            Some(s"{'metadata': {'key': '$mValue'}}"))
        }

        "not create an entity with too long metadata value" in {
          val mValue = "a".repeat(AppConfig.Limits.Metadata.maxValueLength + 1)
          val response = modifyEntity[ErrorResponse](Post, s"${pathPrefix}meta_7", StatusCodes.BadRequest,
            Some(s"{'metadata': {'key': '$mValue'}}"))
          response.error should include ("'metadata.key' is too long")
        }

        val maxValues = (1 to AppConfig.Limits.Metadata.maxValues)
          .map(i => s"'$i'")
          .reduce((a, b) => s"$a, $b")

        "create an entity with maximum count of metadata values in one property" in {
          modifyEntity[SuccessResponse](Post, s"${pathPrefix}meta_8", StatusCodes.OK,
            Some(s"{'metadata': {'key': [$maxValues]}}"))
        }

        "not create an entity with too many metadata values in one property" in {
          val maxValues2 = maxValues + ", 'value'"
          val response = modifyEntity[ErrorResponse](Post, s"${pathPrefix}meta_9", StatusCodes.BadRequest,
            Some(s"{'metadata': {'key': [$maxValues2]}}"))
          response.error should include ("'metadata.key' has too many values")
        }

        "create an entity with mixed (and valid) metadata values" in {
          modifyEntity[SuccessResponse](Post, s"${pathPrefix}meta_10", StatusCodes.OK,
            Some(
              "{'metadata': {" +
                "'keyEmpty': ''," +
                "'keySomeValue': 'all praise the garlic bread'," +
                "'specialChars': '(*&$#(*FJSDHF)#://\\\"+=-%~` grząść aaa 123'," +
                "'array': ['', 'aaa', 'vvvv', '``~~**^$#', '\\\\', '123', '']," +
                "'reduceableArray': ['item']," + // only one item -> should collapse into just a scalar
                "'emptyArray': []" + // empty arrays should be ignored
                "}}"
            ))
        }

        // GET
        "return an entity with mixed values" in {
          Get(s"${pathPrefix}meta_10") ~> resource.route ~> check {
            status should be (StatusCodes.OK)
            contentType should be (ContentTypes.`application/json`)
            val response = responseAs[TEnt]
            val meta = response.metadata.value
            meta.keys.toSeq.length should be (5)
            meta("keyEmpty") should be (Left(""))
            meta("keySomeValue") should be (Left("all praise the garlic bread"))
            meta("array").value.length should be (7)
            meta("reduceableArray") should be (Left("item"))
            meta.keys should not contain ("emptyArray")
          }
        }

        "(parent) return a set of entities with metadata" in {
          Get(pathPrefix) ~> parentResource.route ~> check {
            status should be (StatusCodes.OK)
            contentType should be (ContentTypes.`application/json`)
            val response = responseAs[TParent]
            val set: SetClientModel[TEnt] = response.getSet.value
            set.items.exists({ ent => ent.metadata match
              case Some(m) => m.keys.toSeq.nonEmpty
              case _ => false
            }) should be (true)
          }
        }

        // PATCH
        "add a new metadata field" in {
          modifyEntity[SuccessResponse](Patch, s"${pathPrefix}meta_1", StatusCodes.OK,
            Some("{'metadata': {'newKey': 'newVal'}}"))
        }
        "add several new metadata fields" in {
          modifyEntity[SuccessResponse](Patch, s"${pathPrefix}meta_1", StatusCodes.OK,
            Some("{'metadata': {'newKey2': 'newVal2', 'newKey3': ['1', '2']}}"))
        }
        "retrieve the modified metadata fields (1)" in {
          Get(s"${pathPrefix}meta_1") ~> resource.route ~> check {
            val response = responseAs[TEnt]
            val meta = response.metadata.value
            meta.keys.toSeq.length should be (3)
            meta("newKey") should be (Left("newVal"))
            meta("newKey3") should be (Right(Seq("1", "2")))
          }
        }
        "set existing metadata fields to new values and add new one" in {
          modifyEntity[SuccessResponse](Patch, s"${pathPrefix}meta_1", StatusCodes.OK,
            Some("{'metadata': {'newKey2': ['array', 'array'], 'newKey3': 'now a scalar', 'newnew': 'newnew'}}"))
        }
        "not unset a single array element" in {
          val response = modifyEntity[ErrorResponse](Patch, s"${pathPrefix}meta_1",
            StatusCodes.BadRequest, Some("{'metadata': {'newKey2': ['@unset', 'aaa']}}"))
          response.error should include ("metadata.newKey2[0]")
          response.error should include ("cannot be unset on its own")
        }
        "retrieve the modified metadata fields (2)" in {
          Get(s"${pathPrefix}meta_1") ~> resource.route ~> check {
            val response = responseAs[TEnt]
            val meta = response.metadata.value
            meta.keys.toSeq.length should be (4)
            meta("newKey2") should be (Right(Seq("array", "array")))
            meta("newKey3") should be (Left("now a scalar"))
            meta("newnew") should be (Left("newnew"))
          }
        }
        "unset existing metadata fields, update one, add one" in {
          modifyEntity[SuccessResponse](Patch, s"${pathPrefix}meta_1", StatusCodes.OK,
            Some("{'metadata': {'newKey2': '@unset', 'newKey3': '@unset', 'newnew': 'newnewnew', 'last': 'last'}}"))
        }
        "retrieve the modified metadata fields (3)" in {
          Get(s"${pathPrefix}meta_1") ~> resource.route ~> check {
            val response = responseAs[TEnt]
            val meta = response.metadata.value
            meta.keys.toSeq.length should be (3)
            meta.keys should not contain ("newKey2")
            meta.keys should not contain ("newKey3")
            meta("newnew") should be (Left("newnewnew"))
            meta("last") should be (Left("last"))
          }
        }
        "not modify anything on an empty update request" in {
          modifyEntity[SuccessResponse](Patch, s"${pathPrefix}meta_1", StatusCodes.OK, Some("{}"))
        }
        "retrieve the modified metadata fields (4)" in {
          Get(s"${pathPrefix}meta_1") ~> resource.route ~> check {
            val response = responseAs[TEnt]
            val meta = response.metadata.value
            meta.keys.toSeq.length should be (3)
          }
        }

        // PATCH pre-update checks
        "in an update, not add more metadata keys than allowed" in {
          val response = modifyEntity[ErrorResponse](Patch, s"${pathPrefix}meta_1",
            StatusCodes.BadRequest, Some(s"{'metadata': {$maxProps}}"))
          response.error should include ("Pre-update check failed")
          response.error should include ("the number of metadata keys would exceed the allowed limit")
        }
        "add max number of metadata keys while removing old ones" in {
          modifyEntity[SuccessResponse](Patch, s"${pathPrefix}meta_1",
            StatusCodes.OK,
            Some(
              s"{'metadata': {" +
                "'newKey': '@unset'," +
                "'newnew': '@unset'," +
                "'last': '@unset'," +
                s"$maxProps" +
                s"}}"))
        }
        "not add more metadata keys if the limit is exhausted" in {
          val response = modifyEntity[ErrorResponse](Patch, s"${pathPrefix}meta_1",
            StatusCodes.BadRequest, Some(s"{'metadata': {'new': 'hello'}}"))
          response.error should include ("the number of metadata keys would exceed the allowed limit")
        }
        "in an update, not add more metadata keys if those being removed don't exist" in {
          val response = modifyEntity[ErrorResponse](Patch, s"${pathPrefix}meta_1",
            StatusCodes.BadRequest, Some(s"{'metadata': {'notExists': '@unset', 'notExists2': '@unset', 'new': 'v'}}"))
          response.error should include ("the number of metadata keys would exceed the allowed limit")
        }
        "add one new metadata key while removing an old one" in {
          modifyEntity[SuccessResponse](Patch, s"${pathPrefix}meta_1",
            StatusCodes.OK, Some(s"{'metadata': {'new': 'hello', 'k1': '@unset'}}"))
        }
        "retrieve the modified metadata fields (5)" in {
          Get(s"${pathPrefix}meta_1") ~> resource.route ~> check {
            val response = responseAs[TEnt]
            val meta = response.metadata.value
            meta.keys.toSeq.length should be (AppConfig.Limits.Metadata.maxProperties)
            meta.keys should contain ("k2")
            meta.keys should contain ("k3")
            meta.keys should contain ("new")
            meta("new") should be (Left("hello"))
          }
        }
      }
