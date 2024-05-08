package eu.assistiot.semantic_repo.core.documentation

import akka.Done
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, PostStop}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import com.typesafe.scalalogging.LazyLogging
import eu.assistiot.semantic_repo.core.AppConfig
import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import eu.assistiot.semantic_repo.core.documentation.DocCompilerActor.GetNewJob
import eu.assistiot.semantic_repo.core.storage.Bucket
import org.mongodb.scala.result.UpdateResult

import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

object DocCompilerActor:
  sealed trait Command

  /**
   * Command: check if there are new jobs for you in the DB. If so, execute one.
   */
  final case class GetNewJob() extends Command

/**
 * Actor that runs doc compilation for a given plugin.
 * @param compiler documentation compilation plugin
 * @param cleanupActor reference to an instance of [[DocCleanupActor]]
 * @tparam T class of the documentation plugin
 */
class DocCompilerActor[T <: DocPlugin](val compiler: T, val cleanupActor: ActorRef[DocCleanupActor.Command])
  extends LazyLogging:

  final def apply(timers: TimerScheduler[DocCompilerActor.Command]): Behavior[DocCompilerActor.Command] = Behaviors
    .setup { _ =>
      // Send the GetNewJob command to self periodically, to make sure no jobs were lost somehow
      timers.startTimerWithFixedDelay(GetNewJob(), AppConfig.Scheduled.getNewDocJobs)
      Behaviors
        .receive[DocCompilerActor.Command] { (ctx, message) =>
          message match
            case DocCompilerActor.GetNewJob() =>
              val nextJob = Await.result(DocDbUtils.getNextPendingJob(compiler.name), 5.seconds)
              nextJob match
                case Some(job) => doCompile(ctx, job.info)
                case _ =>
              Behaviors.same
        }
        .receiveSignal {
          case (_, PostStop) =>
            // When stopping, release the plugin's resources
            compiler.release()
            Behaviors.same
        }
    }

  /**
   * Do the actual compilation.
   * @param ctx actor context
   * @param info compilation info
   */
  private def doCompile(ctx: ActorContext[DocCompilerActor.Command], info: MongoModel.DocCompilationInfo): Unit =
    implicit val ec: ExecutionContext = ctx.executionContext
    implicit val sys: ActorSystem[Nothing] = ctx.system

    logger.info(s"Starting job: ${info.jobId}")

    val deleteOldOutputFuture = if info.modelVersion.isDefined then
      // For jobs tied to a model version, delete the old outputs first
      DocStorageUtils.deleteJobContent(info.getOutputDir, Bucket.DocOutput)
    else
      Future { Done }

    val allFuture = deleteOldOutputFuture transformWith { tDone =>
      tDone match
        case Failure(e) =>
          logger.error(s"Failed to remove old outputs for doc job: ${info.jobId} " +
            s"model version: '${info.modelVersion.get}'", e)
        case Success(_) =>

      val dbUpdateFuture: Future[Seq[UpdateResult]] = compiler.compile(info) flatMap {
        // Record the job result in the database
        case Left(_) => DocDbUtils.finishWithSuccess(info)
        case Right(error) => DocDbUtils.finishWithError(info, error.message)
      }
      dbUpdateFuture
    } transform { tUr =>
      logUpdateResult(tUr, info)
      if info.modelVersion.isEmpty then
        // Source files for sandbox jobs are no longer needed, remove them
        cleanupActor ! DocCleanupActor.CleanupSource(info.jobId)
      Success({})
    }
    try {
      Await.ready(allFuture, AppConfig.Limits.Docs.jobExecutionTime)
    } catch {
      case _: TimeoutException =>
        val finishFuture = DocDbUtils.finishWithError(info, "The compilation job timed out.")
          .transform { tUr =>
            logUpdateResult(tUr, info)
            Success({})
          }
        Await.result(finishFuture, 5.seconds)
    }

  /**
   * Helper for logging the result of a "finish job" DB update
   */
  private def logUpdateResult(tUr: Try[Seq[UpdateResult]], info: MongoModel.DocCompilationInfo): Unit = tUr match
    case Success(ur) if ur.forall(_.getModifiedCount == 1) =>
      logger.info(s"Finished doc compilation job: ${info.jobId}")
    case Success(_) =>
      logger.warn(s"Failed to record the result of the doc job: ${info.jobId} (no or partial update)")
    case Failure(e) =>
      logger.error(s"Failed to record the result of the doc job: ${info.jobId}", e)
