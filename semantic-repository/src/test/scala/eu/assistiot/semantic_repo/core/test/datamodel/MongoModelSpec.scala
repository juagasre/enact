package eu.assistiot.semantic_repo.core.test.datamodel

import akka.actor.typed.ActorSystem
import akka.actor.typed.javadsl.Behaviors
import com.typesafe.config.ConfigFactory
import org.mongodb.scala.ObservableFuture
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import org.scalatest.DoNotDiscover

import scala.concurrent.duration.*
import scala.concurrent.ExecutionContextExecutor

/**
 * MongoModel tests. Included in the MongoTests suite collection, thus @DoNotDiscover.
 */
@DoNotDiscover
class MongoModelSpec extends AnyWordSpec, Matchers, ScalaFutures {
  // Set up an actor system
  val config = ConfigFactory.load()
  implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "test", config)
  implicit val executionContext: ExecutionContextExecutor = system.executionContext

  // Timeout for Future-based tests
  implicit val defaultPatience: PatienceConfig = PatienceConfig(10.seconds, 100.millis)

  "MongoDB interface" should {
    "drop the collections and indexes" in {
      MongoModel.dropAll().futureValue should be ("Collections dropped")
      for collection <- MongoModel.allCollections do
        collection.countDocuments().toFuture.futureValue should be (Seq(0))
        collection.listIndexes.toFuture.futureValue.toArray.length should be (0)
    }

    "setup Mongo indexes" in {
      MongoModel.initializeIndexes().futureValue should be ("Indexes initialized")
      MongoModel.namespaceCollection.listIndexes.toFuture.futureValue.toArray.length should be (2)
      MongoModel.modelCollection.listIndexes.toFuture.futureValue.toArray.length should be (2)
      MongoModel.modelVersionCollection.listIndexes.toFuture.futureValue.toArray.length should be (2)
      MongoModel.docJobCollection.listIndexes.toFuture.futureValue.toArray.length should be (4)
    }

    "handle already existing indexes" in {
      MongoModel.initializeIndexes().futureValue should be ("Indexes initialized")
    }
  }
}
