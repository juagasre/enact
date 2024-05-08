package eu.assistiot.semantic_repo.core.test.rest

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.FromResponseUnmarshaller
import eu.assistiot.semantic_repo.core.AppConfig
import eu.assistiot.semantic_repo.core.datamodel.*
import eu.assistiot.semantic_repo.core.rest.json.*
import eu.assistiot.semantic_repo.core.rest.resources.*
import eu.assistiot.semantic_repo.core.test.ApiSpec
import org.scalatest.{DoNotDiscover, EitherValues, Inspectors}
import org.scalatest.concurrent.ScalaFutures

import scala.reflect.ClassTag
import scala.util.control.Breaks.*

@DoNotDiscover
class PagingSpec extends ApiSpec, ScalaFutures, Inspectors, EitherValues:
  // Define the endpoints to use
  trait Endpoint[+TEnt <: HasName with HasMetadata, +TParent <: HasSet[TEnt] : FromResponseUnmarshaller : ClassTag]:
    val pathPrefix: String
    val getResource: Resource
    val postResource: Resource
    val nameProperty: String
    def getSetFromResponse: SetClientModel[TEnt] = responseAs[TParent].getSet.value

  val nsRes = NamespaceResource(controllers.webhook)
  val modelRes = ModelResource(controllers.webhook)
  val mvRes = ModelVersionResource(controllers.webhook)

  object NamespaceEndpoint extends Endpoint[NamespaceClientModel, RootInfoClientModel]:
    val pathPrefix = "/m/"
    val getResource = nsRes
    val postResource = nsRes
    val nameProperty = "namespace"

  object ModelEndpoint extends Endpoint[ModelClientModel, NamespaceClientModel]:
    val pathPrefix = "/m/paging/"
    val getResource = nsRes
    val postResource = modelRes
    val nameProperty = "model"

  object ModelVersionEndpoint extends Endpoint[ModelVersionClientModel, ModelClientModel]:
    val pathPrefix = "/m/paging/paging/"
    val getResource = modelRes
    val postResource = mvRes
    val nameProperty = "version"

  val endpoints = Map(
    "namespace" -> NamespaceEndpoint,
    "model" -> ModelEndpoint,
    "model version" -> ModelVersionEndpoint,
  )

  def postPayload(i: Int): HttpEntity.Strict =
    val payload = (f"{'metadata': {" +
      f"'test': ['${i % 5}', '${(i % 4) + 10}']," +
      f"'s': '${i % 50}%02d'," +
      f"'mark': '1'" +
      "}}").replace('\'', '"')
    HttpEntity(ContentTypes.`application/json`, payload)

  // Set up parent entities first
  "namespace & model endpoints" should {
    "create a test namespace" in {
      Post("/m/paging", postPayload(0)) ~> nsRes.route ~> check {
        status should be (StatusCodes.OK)
      }
    }
    "create a test model" in {
      Post("/m/paging/paging", postPayload(0)) ~> modelRes.route ~> check {
        status should be (StatusCodes.OK)
      }
    }
  }

  // Do the actual testing
  for ((key, endpoint) <- endpoints)
    testOne(key, endpoint)

  def testOne[TEnt <: HasName with HasMetadata, TParent <: HasSet[TEnt]]
  (key: String, endpoint: Endpoint[TEnt, TParent]): Unit =
    val getRoute: Route = endpoint.getResource.route
    val postRoute: Route = endpoint.postResource.route

    s"$key endpoint" should {
      "create 200 new entities" in {
        for (i <- 1 to 200)
          Post(s"${endpoint.pathPrefix}p_$i", postPayload(i)) ~> postRoute ~> check {
            status should be (StatusCodes.OK)
          }
      }

      var allEntities: Seq[TEnt] = Seq()

      "show the first page by default" in {
        Get(endpoint.pathPrefix) ~> getRoute ~> check {
          val set = endpoint.getSetFromResponse
          set.page should be (1)
          set.pageSize should be (AppConfig.Limits.defaultPageSize)
          set.totalCount should be >= 200
          set.inViewCount should be (set.pageSize)
          set.items.toSeq.length should be (set.inViewCount)
          // toSet should remove any duplicates
          set.items.toSet.size should be (set.inViewCount)

          allEntities ++= set.items
        }
      }

      // invalid page numbers
      "not accept a string as page number" in {
        Get(endpoint.pathPrefix + "?page=aaa") ~> getRoute ~> check {
          status should be (StatusCodes.BadRequest)
          val response = responseAs[ErrorResponse]
          response.error should include ("'page'")
          response.error should include ("must be an integer")
        }
      }
      "not accept a float as page number" in {
        Get(endpoint.pathPrefix + "?page=10.5") ~> getRoute ~> check {
          status should be (StatusCodes.BadRequest)
          val response = responseAs[ErrorResponse]
          response.error should include ("'page'")
          response.error should include ("must be an integer")
        }
      }
      "not accept a zero page number" in {
        Get(endpoint.pathPrefix + "?page=0") ~> getRoute ~> check {
          status should be (StatusCodes.BadRequest)
          val response = responseAs[ErrorResponse]
          response.error should include ("Page number cannot be lower than 1")
        }
      }
      "not accept a negative page number" in {
        Get(endpoint.pathPrefix + "?page=-20") ~> getRoute ~> check {
          status should be (StatusCodes.BadRequest)
          val response = responseAs[ErrorResponse]
          response.error should include ("Page number cannot be lower than 1")
        }
      }

      // happy path
      "show the first page when asked explicitly" in {
        Get(endpoint.pathPrefix + "?page=1") ~> getRoute ~> check {
          val set = endpoint.getSetFromResponse
          set.page should be (1)
          set.items.toSet should be (allEntities.toSet)
        }
      }

      "show the second page" in {
        Get(endpoint.pathPrefix + "?page=2") ~> getRoute ~> check {
          val set = endpoint.getSetFromResponse
          set.page should be (2)
          set.pageSize should be (AppConfig.Limits.defaultPageSize)
          set.totalCount should be >= 200
          set.inViewCount should be (set.pageSize)
          set.items.toSet.intersect(allEntities.toSet) shouldBe empty

          allEntities ++= set.items
        }
      }

      // invalid page size
      "not accept a string as page size" in {
        Get(endpoint.pathPrefix + "?page_size=aaa") ~> getRoute ~> check {
          status should be (StatusCodes.BadRequest)
          val response = responseAs[ErrorResponse]
          response.error should include ("'page_size'")
          response.error should include ("must be an integer")
        }
      }
      "not accept a float as page size" in {
        Get(endpoint.pathPrefix + "?page_size=20.5") ~> getRoute ~> check {
          status should be (StatusCodes.BadRequest)
          val response = responseAs[ErrorResponse]
          response.error should include ("'page_size'")
          response.error should include ("must be an integer")
        }
      }
      "not accept a zero page size" in {
        Get(endpoint.pathPrefix + "?page_size=0") ~> getRoute ~> check {
          status should be (StatusCodes.BadRequest)
          val response = responseAs[ErrorResponse]
          response.error should include ("Page size cannot be smaller than 1")
        }
      }
      "not accept a negative page size" in {
        Get(endpoint.pathPrefix + "?page_size=-20") ~> getRoute ~> check {
          status should be (StatusCodes.BadRequest)
          val response = responseAs[ErrorResponse]
          response.error should include ("Page size cannot be smaller than 1")
        }
      }
      "not accept a page size exceeding the limit" in {
        Get(endpoint.pathPrefix + "?page_size=" + (AppConfig.Limits.maxPageSize + 1)) ~> getRoute ~> check {
          status should be (StatusCodes.BadRequest)
          val response = responseAs[ErrorResponse]
          response.error should include ("Maximum allowed page size")
        }
      }

      // more paging
      "show the first page, page size = 50" in {
        Get(endpoint.pathPrefix + "?page_size=50") ~> getRoute ~> check {
          val set = endpoint.getSetFromResponse
          set.page should be (1)
          set.pageSize should be (50)
          set.inViewCount should be (set.pageSize)
          set.items.toSet.intersect(allEntities.toSet).size should be (allEntities.length)
        }
      }

      "show the second page, page size = 50" in {
        Get(endpoint.pathPrefix + "?page=2&page_size=50") ~> getRoute ~> check {
          val set = endpoint.getSetFromResponse
          set.page should be (2)
          set.pageSize should be (50)
          set.inViewCount should be (set.pageSize)
          set.items.toSet.intersect(allEntities.toSet) shouldBe empty
        }
      }

      "show the second page, page size = 10" in {
        Get(endpoint.pathPrefix + "?page=2&page_size=10") ~> getRoute ~> check {
          val set = endpoint.getSetFromResponse
          set.page should be (2)
          set.pageSize should be (10)
          set.inViewCount should be (set.pageSize)
          set.items.toSet.intersect(allEntities.toSet).size should be (10)
        }
      }

      "iterate over all pages" in {
        var pages = 2
        breakable {
          for (i <- 3 to 100)
            var end = false

            Get(endpoint.pathPrefix + s"?page=$i") ~> getRoute ~> check {
              val set = endpoint.getSetFromResponse
              set.page should be (i)
              set.pageSize should be (AppConfig.Limits.defaultPageSize)
              set.inViewCount should be <= set.pageSize

              if (set.inViewCount == 0)
                end = true
                allEntities.length should be (set.totalCount)
              else
                set.items.toSet.intersect(allEntities.toSet) shouldBe empty
                allEntities ++= set.items
                pages = i
            }
            if (end) break
        }

        (pages * AppConfig.Limits.defaultPageSize) should be >= allEntities.length
        ((pages - 1) * AppConfig.Limits.defaultPageSize) should be < allEntities.length
        allEntities.toSet.size should be (allEntities.length)
      }

      "return nothing for very high page numbers" in {
        Get(endpoint.pathPrefix + "?page=500000") ~> getRoute ~> check {
          val set = endpoint.getSetFromResponse
          set.page should be (500000)
          set.pageSize should be (AppConfig.Limits.defaultPageSize)
          set.inViewCount should be (0)
          set.items.size should be (0)
          set.totalCount should be >= 200
        }
      }

      // Filtering parameters
      val badFilterKeys = Seq(
        ("empty", ""),
        ("unknown", "test"),
        ("general metadata", "metadata"),
        ("empty metadata", "metadata."),
        ("too many wildcard levels", "metadata.test.test"),
        ("non-existent wildcard", "dummy.test"),
      )

      for (name, filterKey) <- badFilterKeys do
        s"not accept $name filter key" in {
          Get(endpoint.pathPrefix + s"?f.$filterKey=123") ~> getRoute ~> check {
            status should be (StatusCodes.BadRequest)
            val response = responseAs[ErrorResponse]
            response.error should include (s"'f.$filterKey'")
            response.error should include ("Invalid filtering key")
          }
        }

      "accept several metadata filter keys" in {
        Get(endpoint.pathPrefix + "?f.metadata.test1=123&f.metadata.test2=234") ~> getRoute ~> check {
          status should be (StatusCodes.OK)
          val set = endpoint.getSetFromResponse
          set.page should be (1)
        }
      }

      "filter by main entity name" in {
        Get(endpoint.pathPrefix + s"?f.${endpoint.nameProperty}=p_100") ~> getRoute ~> check {
          status should be (StatusCodes.OK)
          val set = endpoint.getSetFromResponse
          set.page should be (1)
          set.inViewCount should be (1)
          set.items.head.getName should be ("p_100")
        }
      }

      "filter by metadata key (single filter)" in {
        Get(endpoint.pathPrefix + s"?page_size=50&f.metadata.test=3") ~> getRoute ~> check {
          status should be (StatusCodes.OK)
          val set = endpoint.getSetFromResponse
          set.page should be (1)
          set.inViewCount should be (40)
          set.totalCount should be (40)
        }
      }

      "filter by metadata key (two filters)" in {
        Get(endpoint.pathPrefix + s"?page_size=50&f.metadata.test=3&f.metadata.test=12") ~> getRoute ~> check {
          status should be (StatusCodes.OK)
          val set = endpoint.getSetFromResponse
          set.page should be (1)
          set.inViewCount should be (10)
          set.totalCount should be (10)
        }
      }

      "filter by metadata key and entity name" in {
        Get(endpoint.pathPrefix + s"?f.metadata.test=0&f.${endpoint.nameProperty}=p_50") ~> getRoute ~> check {
          status should be (StatusCodes.OK)
          val set = endpoint.getSetFromResponse
          set.page should be (1)
          set.inViewCount should be (1)
          set.items.head.getName should be ("p_50")
        }
      }

      // Sorting parameters
      for (name, sortKey) <- badFilterKeys do
        s"not accept $name sorting key" in {
          Get(endpoint.pathPrefix + s"?sort_by=$sortKey") ~> getRoute ~> check {
            status should be (StatusCodes.BadRequest)
            val response = responseAs[ErrorResponse]
            response.error should include (s"'$sortKey'")
            response.error should include ("Invalid sorting key")
          }
        }

      "accept metadata sort order" in {
        Get(endpoint.pathPrefix + "?sort_by=metadata.test") ~> getRoute ~> check {
          status should be (StatusCodes.OK)
          val set = endpoint.getSetFromResponse
          set.page should be (1)
        }
      }

      "not accept an invalid sort order" in {
        Get(endpoint.pathPrefix + s"?sort_by=metadata.test&order=blah") ~> getRoute ~> check {
          status should be (StatusCodes.BadRequest)
          val response = responseAs[ErrorResponse]
          response.error should include ("Sort order must be")
        }
      }

      "sort by entity name (ascending by default)" in {
        Get(endpoint.pathPrefix + s"?sort_by=${endpoint.nameProperty}") ~> getRoute ~> check {
          status should be (StatusCodes.OK)
          val set = endpoint.getSetFromResponse
          set.inViewCount should be (set.pageSize)
          for (i1, i2) <- set.items.zip(set.items.tail) do
            i1.getName should be <= i2.getName
        }
      }

      "sort by entity name (ascending explicitly)" in {
        Get(endpoint.pathPrefix + s"?sort_by=${endpoint.nameProperty}&order=ascending") ~> getRoute ~> check {
          status should be (StatusCodes.OK)
          val set = endpoint.getSetFromResponse
          set.inViewCount should be (set.pageSize)
          for (i1, i2) <- set.items.zip(set.items.tail) do
            i1.getName should be <= i2.getName
        }
      }

      "sort by entity name (descending explicitly)" in {
        Get(endpoint.pathPrefix + s"?sort_by=${endpoint.nameProperty}&order=descending") ~> getRoute ~> check {
          status should be (StatusCodes.OK)
          val set = endpoint.getSetFromResponse
          set.inViewCount should be (set.pageSize)
          for (i1, i2) <- set.items.zip(set.items.tail) do
            i1.getName should be >= i2.getName
        }
      }

      "sort by a metadata field (descending)" in {
        Get(endpoint.pathPrefix + "?f.metadata.mark=1&sort_by=metadata.s&order=descending")
          ~> getRoute ~> check {
          status should be (StatusCodes.OK)
          val set = endpoint.getSetFromResponse
          set.inViewCount should be (set.pageSize)
          for (i1, i2) <- set.items.zip(set.items.tail) do
            val l = i1.metadata.get("s").left.value
            val r = i2.metadata.get("s").left.value
            l should be >= r
        }
      }
    }
