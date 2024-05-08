package eu.assistiot.semantic_repo.core.test.rest

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.*
import akka.http.scaladsl.server.*
import akka.http.scaladsl.server.Directives.*
import eu.assistiot.semantic_repo.core.datamodel.*
import eu.assistiot.semantic_repo.core.rest.resources.*
import eu.assistiot.semantic_repo.core.rest.json.*
import eu.assistiot.semantic_repo.core.storage.Bucket
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.testkit.RouteTestTimeout
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.Sink
import eu.assistiot.semantic_repo.core.documentation.{DocCleanupActor, DocDbUtils, DocManager}
import eu.assistiot.semantic_repo.core.Guardian
import eu.assistiot.semantic_repo.core.rest.ObjectPathContext
import org.bson.types.ObjectId
import org.mongodb.scala.*
import org.mongodb.scala.model.Filters as f
import org.scalatest.{DoNotDiscover, Inspectors}
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.Futures.{interval, timeout}
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration.*

@DoNotDiscover
class DocModelSpec extends DocGenSpec, Inspectors:
  val docResource = DocModelResource(systemInternal)
  val docRoute = Route.seal(
    ModelVersionResource(controllers.webhook).route ~
    docResource.route
  )

  // Use the overwrite param in the general tests to avoid problems with submitting to the same model version
  runDocGenTests(Some("/m/test/model/1.0.0"), Some("&overwrite=1"))

  "doc model endpoint" should {
    val body = makeDocBody("several_files")

    "redirect from the 'latest' pointer (no file path)" in {
      Get("/m/test/model/latest/doc") ~> docRoute ~> check {
        status should be (StatusCodes.TemporaryRedirect)
        val hLoc: HttpHeader = header("Location").get
        hLoc.value() should endWith ("/m/test/model/1.1.0/doc")
      }
    }

    "redirect from the 'latest' pointer (with file path)" in {
      Get("/m/test/model/latest/doc/img/image.png") ~> docRoute ~> check {
        status should be (StatusCodes.TemporaryRedirect)
        val hLoc: HttpHeader = header("Location").get
        hLoc.value() should endWith ("/m/test/model/1.1.0/doc/img/image.png")
      }
    }

    "not allow retrieving model job details via the sandbox endpoint" in {
      Post("/m/test/model/1.1.0/doc_gen?plugin=markdown", body) ~> docRoute ~> check {
        status should be (StatusCodes.OK)
      }
      val jobInfo: DocumentationJobClientModel = Get("/m/test/model/1.1.0/") ~> docRoute ~> check {
        status should be (StatusCodes.OK)
        val mv: ModelVersionClientModel = responseAs[ModelVersionClientModel]
        mv.documentation.isDefined should be (true)
        mv.documentation.get
      }
      Get(s"/dg/${jobInfo.jobId}") ~> docRoute ~> check {
        status should be (StatusCodes.NotFound)
      }
    }

    "not allow submitting to the 'latest' tag" in {
      Post("/m/test/model/latest/doc_gen?plugin=markdown", body) ~> docRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val response: ErrorResponse = responseAs[ErrorResponse]
        response.error should include ("'latest' tag is not allowed for this endpoint")
      }
    }

    "not allow submitting to a nonexistent model" in {
      Post("/m/namespace/doesnotexist/1.0.0/doc_gen?plugin=markdown", body) ~> docRoute ~> check {
        status should be (StatusCodes.NotFound)
        val response: ErrorResponse = responseAs[ErrorResponse]
        response.error should include ("Could not find model version")
        response.error should include ("namespace/doesnotexist/1.0.0")
      }
    }

    "not allow submitting a job while one is already in progress" in {
      // Insert a dummy job into the DB – do it this way to avoid pesky race conditions
      val jobId = ObjectId()
      MongoModel.docJobCollection.insertOne(MongoModel.DocumentationJob(
        MongoModel.DocCompilationInfo(
          jobId, "markdown", Some(ObjectPathContext.modelVersionFromPath("test/model2/1.0.0"))
        ),
        MongoModel.DocJobStatus.Started,
        123,
      )).toFuture.futureValue

      // Now, try to submit the duplicate job
      Post("/m/test/model2/1.0.0/doc_gen?plugin=gfm", body) ~> docRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val e: ErrorResponse = responseAs[ErrorResponse]
        e.error should include ("Could not create")
        e.error should include ("a job for this model version is already in progress")
      }

      // Clean up the dummy job
      MongoModel.docJobCollection.deleteOne(f.eq("_id", jobId))
        .toFuture.futureValue
    }

    "accept another job" in {
      Post("/m/test/model/1/doc_gen?plugin=markdown", body) ~> docRoute ~> check {
        status should be (StatusCodes.OK)
      }

      // Make sure everything went fine
      // First try may be rejected because the docs are being generated
      eventually(timeout(15.seconds), interval(2.seconds)) {
        Get("/m/test/model/1") ~> docRoute ~> check {
          val response: ModelVersionClientModel = responseAs[ModelVersionClientModel]
          response.documentation.isDefined should be (true)
          response.documentation.get.status should be (MongoModel.DocJobStatus.Success.status)
        }
      }
    }

    "not allow submitting a new job without the overwrite parameter" in {
      Post("/m/test/model/1/doc_gen?plugin=markdown", body) ~> docRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val e: ErrorResponse = responseAs[ErrorResponse]
        e.error should include ("Could not create")
        e.error should include ("there already is some documentation for this model version")
        e.error should include ("'overwrite=1' query parameter")
      }
    }

    "not delete docs without the force parameter" in {
      Delete("/m/test/model/1/doc") ~> docRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val e: ErrorResponse = responseAs[ErrorResponse]
        e.error should include ("To really perform this action")
        e.error should include ("'force'")
      }
    }

    "delete docs" in {
      Delete("/m/test/model/1/doc?force=1") ~> docRoute ~> check {
        status should be (StatusCodes.OK)
        val s: SuccessResponse = responseAs[SuccessResponse]
        s.message should include ("Deleted documentation")
        s.message should include ("test/model/1")
      }
    }

    "not return deleted docs details" in {
      Get("/m/test/model/1") ~> docRoute ~> check {
        status should be (StatusCodes.OK)
        val response: ModelVersionClientModel = responseAs[ModelVersionClientModel]
        response.documentation should be (None)
      }
    }

    "not serve deleted docs" in {
      Get("/m/test/model/1/doc/") ~> docRoute ~> check {
        status should be (StatusCodes.NotFound)
      }
    }

    "not allow deleting docs by the 'latest' tag" in {
      Delete("/m/test/model/latest/doc?force=1") ~> docRoute ~> check {
        status should be (StatusCodes.BadRequest)
        val e: ErrorResponse = responseAs[ErrorResponse]
        e.error should include ("'latest' tag is not allowed for this endpoint")
      }
    }

    "not delete docs that were already deleted" in {
      Delete("/m/test/model/1/doc?force=1") ~> docRoute ~> check {
        status should be (StatusCodes.NotFound)
        val e: ErrorResponse = responseAs[ErrorResponse]
        e.error should include ("There is no documentation")
        e.error should include ("test/model/1")
      }
    }

    "not delete docs for a nonexistent model version" in {
      Delete("/m/test/model/nopenopenope/doc?force=1") ~> docRoute ~> check {
        status should be (StatusCodes.NotFound)
        val e: ErrorResponse = responseAs[ErrorResponse]
        e.error should include ("There is no documentation")
        e.error should include ("test/model/nopenopenope")
      }
    }
  }

  "doc cleanup actor" should {
    "not remove old model jobs prematurely" in {
      val fileList = S3.listBucket(Bucket.DocSource.name, "")
        .runWith(Sink.seq)
        .futureValue
      val jobIdsInS3 = fileList.map(_.key.split('/')(0)).toSet
      jobIdsInS3.size should be > 5

      val docJobs = MongoModel.docJobCollection.find().toFuture.futureValue
      docJobs.size should be > 5
    }

    "remove old model jobs" in {
      eventually(timeout(30.seconds), interval(5.seconds)) {
        // ugh
        systemInternal ! Guardian.DocCommand(DocManager.CleanupCommand(DocCleanupActor.CleanupModelJobs()))

        // this will fail in the first try... but we will try several times ;)
        val sourceFileList = S3.listBucket(Bucket.DocSource.name, "")
          .runWith(Sink.seq)
          .futureValue
        val sourceJobIds = sourceFileList.map(_.key.split('/')(0)).toSet
        // We've been submitting docs for 3 model versions, but for 1 we've deleted the docs
        // so we should have 2 in the end.
        sourceJobIds.size should be (2)

        val docJobs = MongoModel.docJobCollection.find().toFuture.futureValue
        docJobs.size should be (2)
      }
    }

    "not remove the outputs accidentally" in {
      val outFileList = S3.listBucket(Bucket.DocOutput.name, "", Some("model/"))
        .runWith(Sink.seq)
        .futureValue
      val outJobIds = outFileList.map(
        _.key.split('/').slice(1, 4).reduce((a, b) => a + "/" + b)
      ).toSet
      outJobIds.size should be (2)
    }
  }
