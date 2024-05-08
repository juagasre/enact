package eu.assistiot.semantic_repo.core

import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import eu.assistiot.semantic_repo.core.controller.{WebhookDispatcher, WebhookRunner}
import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import eu.assistiot.semantic_repo.core.documentation.DocManager

import scala.concurrent.Future
import scala.concurrent.duration.*

/**
 * Guardian actor of SemRepo's actor system.
 * Dispatches messages to child actors.
 *
 * TODO: decouple message dispatch from the guardian.
 */
object Guardian:
  sealed trait Command
  case class SetupCommand(binding: Http.ServerBinding) extends Command
  case class ReaperCommand(inner: Reaper.ReaperCommand) extends Command
  case class DocCommand(inner: DocManager.Command) extends Command
  case class WebhookCommand(inner: WebhookDispatcher.Command) extends Command

  /**
   * TODO: use a proper service container? Split actor systems?
   * @param sendRequest HTTP request sending service. To override in tests.
   * @return
   */
  def apply(sendRequest: Option[HttpRequest => Future[HttpResponse]] = None): Behavior[Command] =
    Behaviors.setup { ctx =>
      implicit val sys: ActorSystem[Nothing] = ctx.system

      // Restart the doc plugin manager on failure
      val managerSupervised = Behaviors
        .supervise(DocManager())
        .onFailure[Throwable](
          SupervisorStrategy.restart.withLimit(2, 10.seconds)
        )
      val managerRef = ctx.spawn(managerSupervised, "doc-plugin-manager")

      val sendRequestSome = sendRequest.getOrElse {
        val http = Http()
        (rq: HttpRequest) => http.singleRequest(rq)
      }
      val webhookRunnerRef = ctx.spawn(WebhookRunner(sendRequestSome)(), "webhook-runner")
      val webhookDispatcherRef = ctx.spawn(WebhookDispatcher(webhookRunnerRef)(), "webhook-dispatcher")
      var reaperRef: Option[ActorRef[Reaper.ReaperCommand]] = None

      Behaviors.receiveMessage[Command] { message =>
        message match
          case SetupCommand(httpBinding) =>
            reaperRef = Some(ctx.spawn(Reaper(httpBinding, MongoModel.mongoClient), name = "reaper"))
            Behaviors.same
          case ReaperCommand(inner) =>
            reaperRef match
              case Some(rr) => rr ! inner
              case _ =>
            Behaviors.same
          case DocCommand(inner) =>
            managerRef ! inner
            Behaviors.same
          case WebhookCommand(inner) =>
            webhookDispatcherRef ! inner
            Behaviors.same
      }
    }

