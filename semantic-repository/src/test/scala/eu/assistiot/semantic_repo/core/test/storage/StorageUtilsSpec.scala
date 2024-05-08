package eu.assistiot.semantic_repo.core.test.storage

import akka.actor.typed.ActorSystem
import akka.actor.typed.javadsl.Behaviors
import akka.stream.alpakka.s3.BucketAccess
import akka.stream.alpakka.s3.scaladsl.S3
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.Futures.{interval, timeout}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import eu.assistiot.semantic_repo.core.storage.{Bucket, StorageUtils}
import org.scalatest.DoNotDiscover

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContextExecutor}

@DoNotDiscover
class StorageUtilsSpec extends AnyWordSpec, Matchers, StorageUtils, ScalaFutures {
  // Set up an actor system
  val config = ConfigFactory.load()
  implicit val system: ActorSystem[Any] = ActorSystem(Behaviors.empty, "test", config)
  implicit val executionContext: ExecutionContextExecutor = system.executionContext

  // Timeout for Future-based tests
  implicit val defaultPatience: PatienceConfig = PatienceConfig(10.seconds, 100.millis)

  "storage utils" should {
    // on slower CI runners, the storage can take a while to spin up, hence we try to connect several times
    eventually(timeout(20.seconds), interval(2.seconds)) {
      "setup S3-compatible buckets" in {
        setupBuckets().futureValue should be ("Buckets initialized")
        S3.checkIfBucketExists(Bucket.DataModel.name).futureValue should be (BucketAccess.AccessGranted)
        S3.checkIfBucketExists(Bucket.DocSource.name).futureValue should be (BucketAccess.AccessGranted)
        S3.checkIfBucketExists(Bucket.DocOutput.name).futureValue should be (BucketAccess.AccessGranted)
      }
    }

    "handle already existing buckets" in {
      // At this point, the buckets were created in the previous test.
      // We are checking here whether the app can simply check whether the bucket exists, and,
      // in such a case not attempt to create it again.
      setupBuckets().futureValue should be ("Buckets initialized")
    }
  }
}
