package eu.assistiot.semantic_repo.core.documentation

import akka.Done
import akka.actor.typed.{ActorSystem, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.Sink
import eu.assistiot.semantic_repo.core.AppConfig
import eu.assistiot.semantic_repo.core.storage.Bucket
import org.bson.types.ObjectId

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/**
 * Actor that cleans up the no longer needed files and doc job records.
 *
 * This actor is low-priority, slow, and internally synchronous. Don't yell at it.
 */
object DocCleanupActor:
  sealed trait Command

  /**
   * Command: remove the source files for a doc job
   * @param jobId job identifier
   */
  case class CleanupSource(jobId: ObjectId) extends Command

  /**
   * Command: retrieve expired sandbox jobs from the DB and delete them.
   */
  case class CleanupSandboxJobs() extends Command

  /**
   * Command: remove old jobs tied to a model versions and remove those that are not
   * needed anymore.
   */
  case class CleanupModelJobs() extends Command

  def apply(timers: TimerScheduler[Command]): Behavior[Command] = Behaviors.setup { _ =>
    // Set up a periodic job to clean up expired sandbox jobs
    timers.startTimerWithFixedDelay(CleanupSandboxJobs(), AppConfig.Scheduled.docJobCleanup)
    // and model jobs too
    timers.startTimerWithFixedDelay(CleanupModelJobs(), AppConfig.Scheduled.docJobCleanup)
    receive
  }

  private def receive: Behavior[Command] = Behaviors.receive { (ctx, m) =>
    implicit val as: ActorSystem[Nothing] = ctx.system
    implicit val ec: ExecutionContext = as.executionContext

    def deleteJobContent(jobName: String, bucket: Bucket)(implicit as: ActorSystem[Nothing]) =
      try {
        // Do this synchronously... this way the cleanup tasks should not overwhelm the storage
        Await.ready(DocStorageUtils.deleteJobContent(jobName, bucket), 10.seconds)
      } catch {
        case e: Throwable => ctx.log.error(s"Could not remove files from bucket ${bucket.name} for job $jobName", e)
      }

    m match
      case CleanupSource(jobId) =>
        deleteJobContent(jobId.toString, Bucket.DocSource)
      case CleanupSandboxJobs() =>
        val jobsToDelete = Await.result(DocDbUtils.getOldSandboxJobs, 15.seconds)
        if jobsToDelete.nonEmpty then
          ctx.log.info(s"Deleting ${jobsToDelete.length} expired sandbox jobs")
          for job <- jobsToDelete do
            deleteJobContent(job.info.jobId.toString, Bucket.DocSource)
            deleteJobContent("sandbox/" + job.info.jobId, Bucket.DocOutput)
          val mongoDeleted = Await.result(DocDbUtils.deleteJobs(jobsToDelete.map(_.info.jobId)), 15.seconds)
          ctx.log.info(s"Deleted $mongoDeleted expired sandbox jobs from Mongo.")
      case CleanupModelJobs() =>
        // this could fail in extreme cases...
        val jobs = Await.result(DocDbUtils.getOldModelJobs, 15.seconds)
        if jobs.nonEmpty then
          ctx.log.info(s"Deleting ${jobs.length} old model jobs.")
          for job <- jobs do
            deleteJobContent(job.info.jobId.toString, Bucket.DocSource)
          val mongoDeleted = Await.result(DocDbUtils.deleteJobs(jobs.map(_.info.jobId)), 15.seconds)
          ctx.log.info(s"Deleted $mongoDeleted expired model jobs from Mongo.")

    Behaviors.same
  }
