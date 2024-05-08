package eu.assistiot.semantic_repo.core.documentation

import akka.actor.typed.{ActorRef, Behavior, PostStop, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import eu.assistiot.semantic_repo.core.Guardian
import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import eu.assistiot.semantic_repo.core.documentation.local.{MarkdownGitHubPlugin, MarkdownPlugin, RstPlugin}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

/**
 * Main actor managing the access to other documentation actors,
 * including documentation compilation plugins.
 */
object DocManager:

  sealed trait Command

  /**
   * Command: request documentation compilation.
   * @param replyTo actor to reply to
   * @param info parameters for the plugin
   * @param insertInfo extra params for inserting the job to the DB
   */
  case class Compile(replyTo: ActorRef[CompileResponse],
                     info: MongoModel.DocCompilationInfo,
                     insertInfo: JobInsertInfo
                    ) extends Command
  case class CleanupCommand(inner: DocCleanupActor.Command) extends Command

  /**
   * Extra parameters relevant to inserting jobs to the DB.
   * @param overwrite whether to allow inserting a new model job when the model version already has some documentation
   */
  case class JobInsertInfo(overwrite: Boolean)

  sealed trait CompileResponse
  case class CompilationStartedResponse() extends CompileResponse
  case class UserError(message: String) extends CompileResponse
  case class ServerError(message: String) extends CompileResponse

  private case class MaterializedPlugin(actor: ActorRef[DocCompilerActor.Command], plugin: DocPlugin)

  def apply(): Behavior[Command] = Behaviors.setup { ctx =>
    implicit val ec: ExecutionContext = ctx.executionContext

    // Setup the cleanup actor and the compiler actors
    val cleanupActor = ctx.spawn(
      supervised(Behaviors.withTimers(timers => DocCleanupActor(timers))), 
      "doc-cleanup"
    )

    val pluginPairs = for plugin <- DocPluginRegistry.enabledPlugins yield
      plugin.name -> MaterializedPlugin(
        ctx.spawn(
          supervised(Behaviors.withTimers(timers => DocCompilerActor(plugin, cleanupActor)(timers))),
          "doc-plugin-" + plugin.name
        ),
        plugin
      )
    val pluginMap = Map.from(pluginPairs)

    Behaviors.receiveMessage {
      // Compilation request
      case Compile(replyTo, info, insertInfo) =>
        pluginMap.get(info.pluginName) match
          case Some(plugin) =>
            doCompile(plugin, info, insertInfo, cleanupActor) foreach { res => replyTo ! res }
          case None =>
            // No such plugin -> delete the source files
            cleanupActor ! DocCleanupActor.CleanupSource(info.jobId)
            replyTo ! UserError(s"Documentation plugin '${info.pluginName}' is not registered.")
        Behaviors.same
      case CleanupCommand(inner) =>
        cleanupActor ! inner
        Behaviors.same
    }
  }

  /**
   * Dispatch the documentation compilation command to the appropriate plugin.
   * @param plugin plugin to use
   * @param info doc job information
   * @param insertInfo insert info
   * @param cleanupActor reference to the doc cleanup actor
   * @param ec execution context
   * @return future of a response to send to the user
   */
  private def doCompile(plugin: MaterializedPlugin, info: MongoModel.DocCompilationInfo, insertInfo: JobInsertInfo,
                        cleanupActor: ActorRef[DocCleanupActor.Command])
                       (implicit ec: ExecutionContext):
  Future[CompileResponse] =
    // 1. create doc job in DB
    DocDbUtils.createJob(info, insertInfo.overwrite) map { result => result match
      case DocDbUtils.InsertSuccess() =>
        // 2. enqueue the job at the compiler
        plugin.actor ! DocCompilerActor.GetNewJob()
        CompilationStartedResponse()
      case ie: DocDbUtils.InsertError =>
        val message = s"Could not create documentation compilation job: ${ie.error}."
        // Could not create job -> delete the source files
        cleanupActor ! DocCleanupActor.CleanupSource(info.jobId)
        if ie.isServerError then
          ServerError(message)
        else
          UserError(message)
    }

  /**
   * Wraps the actor behavior in a supervision "shell" to restart it on failures.
   * @param behavior inner behavior
   * @return wrapped behavior
   */
  private def supervised[T](behavior: Behavior[T]): Behavior[T] =
    Behaviors
      .supervise(behavior)
      .onFailure[Throwable](
        SupervisorStrategy.restart.withLimit(1, 5.seconds)
      )
