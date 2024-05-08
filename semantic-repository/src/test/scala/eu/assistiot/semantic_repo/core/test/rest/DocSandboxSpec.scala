package eu.assistiot.semantic_repo.core.test.rest

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.*
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.server.*
import akka.http.scaladsl.server.directives.ContentTypeResolver
import akka.http.scaladsl.unmarshalling.FromResponseUnmarshaller
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.Sink
import eu.assistiot.semantic_repo.core.{Guardian, datamodel}
import eu.assistiot.semantic_repo.core.datamodel.*
import eu.assistiot.semantic_repo.core.documentation.{DocCleanupActor, DocManager}
import eu.assistiot.semantic_repo.core.rest.resources.*
import eu.assistiot.semantic_repo.core.rest.json.*
import eu.assistiot.semantic_repo.core.storage.Bucket
import eu.assistiot.semantic_repo.core.test.ApiSpec
import org.mongodb.scala.*
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.Futures.{interval, timeout}
import org.scalatest.concurrent.ScalaFutures

import java.io.File
import scala.concurrent.{Await, ExecutionContextExecutor}
import scala.concurrent.duration.*
import scala.reflect.ClassTag

@DoNotDiscover
class DocSandboxSpec extends DocGenSpec:
  val docResource = DocSandboxResource(systemInternal)
  val docRoute = Route.seal(docResource.route)

  "doc sandbox meta endpoint" should {
    "list all available plugins" in {
      Get("/doc_gen") ~> docRoute ~> check {
        status should be (StatusCodes.OK)
        val response = responseAs[DocMetaClientModel]
        val plugins: Map[String, DocPluginClientModel] = response.enabledPlugins
        plugins.size should be (3)
        plugins.keys should contain allOf ("rst", "markdown", "gfm")
        plugins("markdown").description should not be empty
        plugins("markdown").allowedFileExtensions should contain allOf ("md", "markdown", "png", "jpg")
      }
    }
  }

  "doc sandbox submission endpoint" should {
    "not accept a request without the plugin parameter" in {
      Post("/doc_gen") ~> docRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val response = responseAs[ErrorResponse]
        response.error should include ("compilation plugin name must be specified")
      }
    }

    "not accept an unknown doc plugin name" in {
      Post("/doc_gen?plugin=unknown") ~> docRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val response = responseAs[ErrorResponse]
        response.error should include ("is not registered")
        response.error should include ("'unknown'")
      }
    }

    "not accept a request with no body" in {
      Post("/doc_gen?plugin=markdown") ~> docRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val response = responseAs[ErrorResponse]
        response.error should include ("Unsupported content type of request body")
      }
    }
  }

  runDocGenTests(None, None)

  "doc cleanup actor" should {
    "remove the source files" in {
      eventually(timeout(10.seconds), interval(2.seconds)) {
        val fileList = S3.listBucket(Bucket.DocSource.name, "")
          .runWith(Sink.seq)
          .futureValue
        fileList shouldBe empty
      }
    }

    "not remove sandbox jobs prematurely" in {
      val fileList = S3.listBucket(Bucket.DocOutput.name, "", Some("sandbox/"))
        .runWith(Sink.seq)
        .futureValue
      fileList should not be empty
    }

    "remove expired sandbox jobs" in {
      eventually(timeout(30.seconds), interval(5.seconds)) {
        // ugh
        systemInternal ! Guardian.DocCommand(DocManager.CleanupCommand(DocCleanupActor.CleanupSandboxJobs()))

        // this will fail in the first try... but we will try several times ;)
        val fileList = S3.listBucket(Bucket.DocOutput.name, "")
          .runWith(Sink.seq)
          .futureValue
        fileList shouldBe empty

        val docJobs = MongoModel.docJobCollection.find().toFuture.futureValue
        docJobs shouldBe empty
      }
    }
  }