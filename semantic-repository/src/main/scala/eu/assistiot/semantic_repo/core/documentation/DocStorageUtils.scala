package eu.assistiot.semantic_repo.core.documentation

import akka.Done
import akka.stream.Materializer
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.Sink
import eu.assistiot.semantic_repo.core.storage.Bucket

import scala.concurrent.{ExecutionContext, Future}

object DocStorageUtils:
  /**
   * Deletes the content for a job, either in the source or output S3 bucket.
   * @param path path to the job
   * @param bucket S3 bucket
   * @param mat stream materializer
   * @return future of Done
   */
  def deleteJobContent(path: String, bucket: Bucket)(implicit mat: Materializer): Future[Done] =
    implicit val ec: ExecutionContext = mat.executionContext
    // This is hugely important. Without this, when deleting "1" you also delete "1.0.0",
    // which can be two totally different things...
    val pathSlash = if path.endsWith("/") then path else path + "/"
    S3.deleteObjectsByPrefix(bucket.name, Some(pathSlash))
      .runWith(Sink.seq)
      .map(_ => Done)
