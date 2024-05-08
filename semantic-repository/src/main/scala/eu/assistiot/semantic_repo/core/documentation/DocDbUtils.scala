package eu.assistiot.semantic_repo.core.documentation

import akka.Done
import com.mongodb.client.result.DeleteResult
import eu.assistiot.semantic_repo.core.AppConfig
import eu.assistiot.semantic_repo.core.Exceptions.{NotFoundException, PreValidationException}
import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import eu.assistiot.semantic_repo.core.datamodel.MongoModel.DocCompilationInfo
import eu.assistiot.semantic_repo.core.rest.{AbstractModelVersionContext, modelVersionFilter}
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.mongodb.scala.*
import org.mongodb.scala.model.Filters as f
import org.mongodb.scala.model.Sorts as s
import org.mongodb.scala.model.Updates as u
import org.mongodb.scala.result.UpdateResult

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

/**
 * Database access utilities for managing documentation jobs.
 */
object DocDbUtils:
  sealed trait InsertResult
  case class InsertSuccess() extends InsertResult
  enum InsertError(val error: String, val isServerError: Boolean) extends InsertResult:
    case DuplicateJob extends InsertError("a job for this model version is already in progress", false)
    case ModelVersionNotFound extends InsertError("the model version could not be found", false)
    case NeedsOverwrite extends InsertError("there already is some documentation for this model version – " +
      "to overwrite it, use the 'overwrite=1' query parameter", false)
    case DbError extends InsertError("unknown database error", true)

  private case class InsertException(error: InsertError) extends Exception

  /**
   * Create a new job in the DB.
   * @param info job parameters
   * @param overwrite whether to allow inserting a new model job when the model version already has some documentation
   * @param ec execution context
   * @return future of the DB insert result
   */
  def createJob(info: MongoModel.DocCompilationInfo, overwrite: Boolean)
               (implicit ec: ExecutionContext): Future[InsertResult] =
    val docJob = MongoModel.DocumentationJob(info, MongoModel.DocJobStatus.Started, getTimestamp)
    val transactionFuture = MongoModel.transaction( { session =>
      // Insert a new job to the job collection
      val insertObs = MongoModel.docJobCollection.insertOne(session, docJob)
      val updateObs = info.modelVersion match
        case Some(mvc) =>
          // If it's a model job, then update also the model version collection
          val mvUpdateFilter = if overwrite then mvc.modelVersionFilter
          else f.and(mvc.modelVersionFilter, f.eq("docJob", null))
          val mvUpdateObs = MongoModel.modelVersionCollection.updateOne(
            session,
            mvUpdateFilter,
            u.set("docJob", docJob)
          ).map { ur =>
            if ur.getMatchedCount == 0 then
              // It is possible that in this case it is the model version that didn't match...
              // but it's very unlikely and can only occur in weird rare conditions.
              // So let's just assume that it is the overwrite parameter that is missing.
              if !overwrite then throw InsertException(InsertError.NeedsOverwrite)
              // In this case, there is only one (rare) possibility
              else throw InsertException(InsertError.ModelVersionNotFound)
            if ur.getModifiedCount == 0 then
              throw InsertException(InsertError.DbError)
          }
          mvUpdateObs.flatMap { _ =>
            // And mark the old doc jobs for deletion
            MongoModel.docJobCollection.updateMany(
              session,
              f.and(
                f.eq("modelVersion", mvc.path),
                f.eq("toDelete", false),
              ),
              u.set("toDelete", true),
            ).flatMap { _ => insertObs }
          }
        case _ => insertObs

      updateObs.map(_ => {}).toSingle
    }, None ).toFuture

    transactionFuture map {
      _ => InsertSuccess()
    } recover {
      case InsertException(ie) => ie
      case e: MongoWriteException => e.getCode match
        case 11000 => InsertError.DuplicateJob
        case _ => InsertError.DbError
      case _ => InsertError.DbError
    }

  /**
   * Record that a job was finished.
   * This handles the update in the doc job collection, and (if needed), in the model version collection.
   * @param info doc job compilation info
   * @param updateBody function from field name prefix to update body for the doc job
   * @param ec execution context
   * @return future of update results
   */
  private def finishJob(info: DocCompilationInfo, updateBody: String => Bson)(implicit ec: ExecutionContext):
  Future[Seq[UpdateResult]] =
    val docJobFut = MongoModel.docJobCollection
      .updateOne(
        f.and(
          f.eq("_id", info.jobId),
          // Just in case: only include jobs in the "started" state
          // This should help avoid partial updates etc.
          f.eq("status", MongoModel.DocJobStatus.Started.status)
        ),
        updateBody("")
      )
      .toFuture
    val futures = info.modelVersion match
      case Some(mvc) => Seq(
        docJobFut,
        MongoModel.modelVersionCollection
          .updateOne(
            f.and(
              mvc.modelVersionFilter,
              f.eq("docJob.status", MongoModel.DocJobStatus.Started.status),
            ),
            updateBody("docJob.")
          )
          .toFuture
      )
      case _ => Seq(docJobFut)
    Future.sequence(futures)

  /**
   * Record that the job failed.
   * @param info doc compilation info
   * @param error error message that can be displayed to the user
   * @return future of update results
   */
  def finishWithError(info: DocCompilationInfo, error: String)(implicit ec: ExecutionContext):
  Future[Seq[UpdateResult]] =
    finishJob(info, { prefix =>
      u.combine(
        u.set(s"${prefix}status", MongoModel.DocJobStatus.Failed.status),
        u.set(s"${prefix}ended", getTimestamp),
        u.set(s"${prefix}error", error),
      )
    })

  /**
   * Record that the job finished successfully.
   * @param info doc compilation info
   * @return future of update results
   */
  def finishWithSuccess(info: DocCompilationInfo)(implicit ec: ExecutionContext): Future[Seq[UpdateResult]] =
    finishJob(info, { prefix =>
      u.combine(
        u.set(s"${prefix}status", MongoModel.DocJobStatus.Success.status),
        u.set(s"${prefix}ended", getTimestamp),
      )
    })

  def getNextPendingJob(pluginName: String): Future[Option[MongoModel.DocumentationJob]] =
    MongoModel.docJobCollection
      .find(f.and(
        f.eq("status", MongoModel.DocJobStatus.Started.status),
        f.eq("pluginName", pluginName),
      ))
      .sort(s.ascending("started"))
      .first()
      .toFutureOption()

  /**
   * Return the details of a sandbox doc compilation job.
   * @param jobId job identifier
   * @return future of a MongoDB object describing the job
   */
  def getSandboxJob(jobId: ObjectId): Future[MongoModel.DocumentationJob] =
    MongoModel.docJobCollection.find(f.and(
      f.eq("_id", jobId),
      f.eq("modelVersion", null),
    )).first.toFuture

  /**
   * Return a list of sandbox jobs that can be removed.
   * @return future of a sequence of jobs
   */
  def getOldSandboxJobs: Future[Seq[MongoModel.DocumentationJob]] =
    MongoModel.docJobCollection.find(
      f.and(
        f.eq("modelVersion", null),
        f.lte("started", getTimestamp - AppConfig.Limits.Docs.sandboxExpiry.toMillis),
        f.ne("status", MongoModel.DocJobStatus.Started.status),
      )
    ).toFuture

  /**
   * Return a list of model jobs that can be removed.
   * @return future of a sequence of jobs
   */
  def getOldModelJobs: Future[Seq[MongoModel.DocumentationJob]] =
    MongoModel.docJobCollection.find(
      f.eq("toDelete", true)
    ).toFuture

  /**
   * Batch-delete documentation jobs from the database.
   * NOTE: this does not remove any files in the buckets.
   * @param jobIds list of job IDs to delete
   * @param ec execution context
   * @return future of the number of jobs that were successfully deleted
   */
  def deleteJobs(jobIds: Iterable[ObjectId])(implicit ec: ExecutionContext): Future[Long] =
    // Batch deletion by 16 jobs
    val deleteFutures = for jobs <- jobIds.grouped(16) yield
      MongoModel.docJobCollection.deleteMany(
        f.or(jobs.map(jobId => f.eq("_id", jobId)).toSeq*)
      ).toFuture
    Future.sequence(deleteFutures.toSeq) map { deleteResults =>
      deleteResults.map(_.getDeletedCount).sum
    }

  /**
   * For a model job, remove the details in the model version collection and mark the
   * job itself for later deletion (see [[getOldModelJobs]]).
   * @param mvc model version context
   * @return future of the transaction
   */
  def markModelJobForDeletion(implicit mvc: AbstractModelVersionContext): Future[Void] =
    MongoModel.transaction( { session =>
      // First, get the model version in question
      MongoModel.modelVersionCollection.find(session, modelVersionFilter).collect.flatMap {
        // Make sure that a doc job exists in this model version
        case Seq(modelVersion) => modelVersion.docJob match
          case Some(docJob) =>
            if docJob.status == MongoModel.DocJobStatus.Started then
              throw PreValidationException(new Exception("The documentation compilation job is in progress."))
            // Remove the doc job from Mongo
            MongoModel.modelVersionCollection.updateOne(
              session,
              modelVersionFilter,
              u.unset("docJob")
            ).zip(
              // And mark the doc job for deletion
              MongoModel.docJobCollection.updateOne(
                session,
                f.eq("_id", docJob.info.jobId),
                u.set("toDelete", true)
              )
            )
          case None => throw NotFoundException()
        case _ => throw NotFoundException()
      }.map(_ => {}).toSingle
    }, None ).toFuture

  private def getTimestamp = System.currentTimeMillis
