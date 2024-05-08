package eu.assistiot.semantic_repo.core.rest

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.Directives.{complete, extractRequestContext, onSuccess}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.FileInfo
import akka.stream.{ClosedShape, Materializer}
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, RunnableGraph, Sink, Source}
import akka.util.ByteString
import eu.assistiot.semantic_repo.core.datamodel.{ErrorResponse, MongoModel, SuccessResponse}
import eu.assistiot.semantic_repo.core.rest.json.*
import eu.assistiot.semantic_repo.core.storage.{Bucket, DigAlgorithm, DigestCalculator, DigestResult}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

/**
 * Helper methods for handling files stored in S3 from the context of Akka HTTP requests.
 */
object FileUtils extends JsonSupport:

  /**
   * File upload helper. Directs a ByteString from Akka HTTP to S3 storage and returns an object describing the
   * metadata of the file.
   * @param bucket S3 bucket
   * @param objectKey key of the object to upload
   * @param metadata metadata obtained from Akka HTTP
   * @param byteSource stream of bytes to upload
   * @param materializer stream materializer
   * @param ec execution context
   * @return Future of an object describing the metadata of the file.
   */
  def uploadFile(bucket: Bucket, objectKey: String, metadata: FileInfo, byteSource: Source[ByteString, Any])
                (implicit materializer: Materializer, ec: ExecutionContext):
  Future[MongoModel.StoredFile] =
    val sinkSize = Sink.fold[Long, ByteString](0)((acc, el) => acc + el.length)
    // Calculate the MD5 manually. This is needed because MinIO and other S3-compatible services will not compute
    // the MD5 for multipart uploads: https://github.com/minio/minio/pull/7609
    val sinkMD5 = DigestCalculator.hexString(DigAlgorithm.MD5).toMat(Sink.head)(Keep.right)
    val sinkS3 = S3.multipartUpload(bucket.name, objectKey, metadata.contentType)

    val (sizeFuture, md5Future, uploadFuture) =
      RunnableGraph
        .fromGraph(GraphDSL.createGraph(sinkSize, sinkMD5, sinkS3)(Tuple3.apply) {
          implicit builder => (sizeS, md5S, s3S) =>
            import GraphDSL.Implicits.*
            val broadcast = builder.add(Broadcast[ByteString](3))
            byteSource ~> broadcast
            broadcast.out(0) ~> sizeS
            // Add an async boundary to allow calculating the MD5 in parallel
            broadcast.out(1) ~> Flow[ByteString].async ~> md5S
            broadcast.out(2) ~> s3S
            ClosedShape
        }).run()

    // Piotr Sowiński, 15.03.2022:
    // If there are two concurrent uploads to the same object, one will finish after the other and the content
    // of the object will be consistent with only one of the uploads.
    // In most cases, that will mean that the second upload will be the one that leaves its metadata in MongoDB.
    // In rare cases where somehow the first upload's metadata is submitted to the DB last, the only meaningful
    // difference will be in the MD5 checksum. The only way to avoid this possibility would be to do a Mongo
    // update within a transaction that would also lock the file to some state, ensuring it is the one we
    // uploaded. This however seems so complicated and error-prone, that it is perhaps better to settle for this
    // compromise.
    for
      size <- sizeFuture
      md5 <- md5Future
      uploadResult <- uploadFuture
    yield
      MongoModel.StoredFile(size, uploadResult.key, md5, metadata.contentType.toString)

  /**
   * Builds an Akka HTTP route for downloading a given file from S3 storage. The file is streamed to the client and
   * the proper Content-Type header is set.
   * @param bucket S3 bucket to use
   * @param storageRef path to the file in the S3 storage
   * @param notFoundMsg message to return to the user if the file was not found (optional)
   * @return Akka HTTP route
   */
  def downloadFileRoute(bucket: Bucket, storageRef: String, notFoundMsg: Option[String]): Route =
    extractRequestContext { ctx =>
      implicit val materializer: Materializer = ctx.materializer
      implicit val ec: ExecutionContextExecutor = ctx.executionContext

      val statFuture = S3.download(bucket.name, storageRef)
        .runWith(Sink.head)

      onSuccess(statFuture) {
        case Some((data: Source[ByteString, _], metadata)) =>
          val contentType: ContentType = metadata.contentType.flatMap( { value =>
            ContentType.parse(value) match
              case Right(ct) => Some(ct)
              case _ => None
          } ).getOrElse(ContentTypes.`application/octet-stream`)
          complete(StatusCodes.OK, HttpEntity(contentType, data))

        case _ => notFoundMsg match
          case Some(msg) => complete(StatusCodes.NotFound, ErrorResponse(msg))
          case _ => complete(StatusCodes.NotFound)
      }
    }
