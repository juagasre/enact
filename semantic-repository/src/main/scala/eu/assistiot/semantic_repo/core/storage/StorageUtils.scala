package eu.assistiot.semantic_repo.core.storage

import akka.Done
import akka.stream.alpakka.s3.BucketAccess
import akka.stream.alpakka.s3.scaladsl.S3
import eu.assistiot.semantic_repo.core.ActorSystemUser

import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
 * Utilities for managing the S3-compatible storage of the repo.
 */
trait StorageUtils extends ActorSystemUser:

  /**
   * Makes sure that the required storage buckets are present.
   * If missing, creates them.
   * @return
   */
  def setupBuckets(): Future[String] =
    val futures: Iterable[Future[Done]] = Bucket.values map { bucket =>
      (bucket, S3.checkIfBucketExists(bucket.name))
    } map { (bucket, bAccess) =>
      // Map access denied to an exception
      // Propagate other exceptions
      bAccess flatMap {
        case BucketAccess.AccessDenied => throw Exception(s"Access denied to bucket $bucket")
        case BucketAccess.NotExists => S3.makeBucket(bucket.name)
        case _ => Future { Done }
      }
    }

    Future.sequence(futures) map {
      list => "Buckets initialized"
    } recoverWith {
      case ex => throw new Exception("Could not connect to storage", ex)
    }

