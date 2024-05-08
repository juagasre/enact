package eu.assistiot.semantic_repo.core

import com.typesafe.scalalogging.LazyLogging
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.*
import akka.actor.typed.scaladsl.ActorContext
import akka.http.scaladsl.Http.ServerBinding
import org.mongodb.scala.MongoClient

import scala.concurrent.ExecutionContext.Implicits.global

private object Reaper:
  trait ReaperCommand
  case object AllDone extends ReaperCommand
  
  def apply(binding: ServerBinding, dbClient: MongoClient): Behavior[ReaperCommand] =
    Behaviors.setup { context =>
      new Reaper(context, binding, dbClient)
    }

import Reaper.ReaperCommand

private class Reaper(context: ActorContext[ReaperCommand], binding: ServerBinding, dbClient: MongoClient)
  extends AbstractBehavior[ReaperCommand](context), LazyLogging:

  import Reaper._

  override def onMessage(msg: ReaperCommand): Behavior[ReaperCommand] = msg match
    case AllDone =>
      binding
        .unbind()
        .onComplete {
          _ =>
            logger.info("Disconnecting from Semantic Repository database")
            dbClient.close()
            logger.info("Shutting down Semantic Repository")
            context.system.terminate()
        }

      Behaviors.stopped
