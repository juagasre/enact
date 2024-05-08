package eu.assistiot.semantic_repo.core

import akka.actor.typed.*
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.AskPattern.*
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import eu.assistiot.semantic_repo.core.controller.*
import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import eu.assistiot.semantic_repo.core.rest.RestApi
import eu.assistiot.semantic_repo.core.storage.StorageUtils
import fr.davit.akka.http.metrics.core.HttpMetrics.*
import fr.davit.akka.http.metrics.prometheus.{PrometheusRegistry, PrometheusSettings}
import io.prometheus.client.CollectorRegistry

import scala.concurrent.ExecutionContextExecutor
import scala.util.{Failure, Success}

object Main extends App, RestApi, StorageUtils:

  // 1. Set up the actor system and the execution context
  implicit val system: ActorSystem[Guardian.Command] = ActorSystem(Guardian(), "http", AppConfig.getConfig)
  implicit val executionContext: ExecutionContextExecutor = system.executionContext

  // 2. Instantiate controllers
  override val controllers = ControllerContainer(system)

  /**
   * Signal the Reaper to perform shutdown of the repo.
   */
  def runReaper(): Unit = system ! Guardian.ReaperCommand(Reaper.AllDone)

  // 3. Initialize the database
  val dbSetup = MongoModel.initializeIndexes()
  dbSetup foreach { message =>
    logger.info(message)
  }

  // 4. Connect to storage (S3-compatible)
  val storageSetup = setupBuckets()
  storageSetup foreach { message =>
    logger.info(message)
  }

  // 5. Start the HTTP server
  val host = AppConfig.Http.host
  val port = AppConfig.Http.port

  val httpSetup = dbSetup.zip(storageSetup) flatMap { _ =>
    Http().newMeteredServerAt(
      host, port,
      controllers.metrics.metricsRegistry
    ).bind(api) recoverWith {
      case ex => throw new Exception(s"REST interface could not bind to $host:$port", ex)
    }
  }
  httpSetup onComplete {
    case Success(binding) =>
      system ! Guardian.SetupCommand(binding)
      logger.info(s"REST interface bound to http:/${binding.localAddress}")
    case Failure(ex) =>
      logger.error(ex.getMessage)
      if (ex.getCause != null) logger.error(ex.getCause.getMessage)
      logger.info("Shutting down Semantic Repository")
      MongoModel.mongoClient.close()
      system.terminate()
  }
