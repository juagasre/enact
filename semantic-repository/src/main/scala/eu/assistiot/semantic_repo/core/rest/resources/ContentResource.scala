package eu.assistiot.semantic_repo.core.rest.resources

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.{ContentType, DateTime, HttpHeader, StatusCodes, MediaType as AkkaMediaType}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.directives.FileInfo
import akka.http.scaladsl.server.{Directive1, Rejection, RejectionHandler, Route}
import akka.stream.Materializer
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import eu.assistiot.semantic_repo.core.controller.WebhookController
import eu.assistiot.semantic_repo.core.datamodel.{ErrorResponse, MongoModel, SuccessResponse}
import eu.assistiot.semantic_repo.core.datamodel.*
import eu.assistiot.semantic_repo.core.rest.*
import eu.assistiot.semantic_repo.core.rest.Directives.*
import eu.assistiot.semantic_repo.core.rest.json.*
import eu.assistiot.semantic_repo.core.storage.Bucket
import fr.davit.akka.http.metrics.core.scaladsl.server.HttpMetricsDirectives.*
import org.mongodb.scala.*
import org.mongodb.scala.bson.BsonNull
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters as f
import org.mongodb.scala.model.Updates as u
import org.mongodb.scala.result.UpdateResult
import spray.json.DefaultJsonProtocol.*
import spray.json.{JsBoolean, JsObject, JsString, JsonWriter}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}
import scala.util.matching.*

object ContentResource:
  final val formatRegexString = "^[a-zA-Z0-9][\\w\\-/+.]{0,99}$"
  val formatRegex = formatRegexString.r

class ContentResource(webhookContr: WebhookController) extends MongoResource:
  import ContentResource.*

  val route: Route = handleExceptions(mongoExceptionHandler) {
    // Standard endpoint
    ignoreTrailingSlash {
      pathLabeled(ModelVersionResource.mvMatcher / "content", "m/:ns/:model/:version/content") {
        (ns, model, version) =>
          implicit val mvContext: ModelVersionContext = ModelVersionContext(ns, model, version)
          parameters("format".optional) { format =>
            postContentRoute(format) ~
            getContentRoute(format) ~
            deleteContentRoute(format)
          }
      }
    } ~
    // Shorthand (/c/...) endpoint
    pathPrefixLabeled(
      "c" / NamespaceResource.nsRegex / ModelResource.modRegex, "m/:ns/:model/:version/content"
    ) {
      (ns, model) =>
        pathEndOrSingleSlash {
          implicit val mvContext: ModelVersionContext = ModelVersionContext(ns, model, "latest")
          // /c/ns/model
          getContentRoute(None)
        } ~
        pathPrefix(ModelVersionResource.verRegex) { version =>
          implicit val mvContext: ModelVersionContext = ModelVersionContext(ns, model, version)
          pathEndOrSingleSlash {
            // /c/ns/model/version
            getContentRoute(None)
          } ~
          path(RemainingPath) { format =>
            // /c/ns/model/version/format
            getContentRoute(Some(format.toString))
          }
        }
    }
  }

  /**
   * Directive validating the format parameter on POST and DELETE – it must be present and match regex.
   * Returns HTTP 400 on unmet assertions.
   */
  private def validateFormat(formatOption: Option[String]): Directive1[String] =
    formatOption match
      case None => complete(StatusCodes.BadRequest, ErrorResponse("Format must be given explicitly with the " +
        "'format' query parameter."))
      case Some(format) =>
        format match
          case formatRegex() => provide(format)
          case _ => complete(StatusCodes.BadRequest, ErrorResponse(
            s"Invalid format string. The 'format' parameter must match regex: '$formatRegexString'."
          ))

  /**
   * Serve the model's content.
   * @param formatOption content format
   * @param mvContext model version context
   * @return
   */
  def getContentRoute(formatOption: Option[String])(implicit mvContext: ModelVersionContext) =
    /**
     * Establishes the model format to download.
     * @param modelVersion ModelVersion object
     * @return (format or None; isFormatSpecifiedExplicitly)
     */
    def getFormat(modelVersion: MongoModel.ModelVersion): (Option[String], Boolean) =
      // TODO: HTTP content negotiation
      formatOption match
        case Some(format) => (Some(format), true)
        case _ =>
          modelVersion.defaultFormat match
            case Some(format) => (Some(format), false)
            case _ => (None, false)

    /**
     * Performs the download for the given model version or returns a wide variety of 404 errors.
     * @param modelVersion model version to download
     * @return Route
     */
    def doDownload(modelVersion: MongoModel.ModelVersion): Route =
      val (formatOption, formatExplicit) = getFormat(modelVersion)
      formatOption match
        case Some(format) =>
          modelVersion.formats.getOrElse(Map()).get(format) match
            case Some(storedFile) => FileUtils.downloadFileRoute(
              Bucket.DataModel,
              storedFile.storageRef,
              Some("The metadata for the requested file " +
                "was found, but the file itself is missing. Please upload it again or delete it.")
            )
            case _ =>
              if (formatExplicit) {
                complete(StatusCodes.NotFound, ErrorResponse(s"The specified format '$format' could not be found " +
                  s"for this model version."))
              } else {
                complete(StatusCodes.NotFound, ErrorResponse(s"The default format for this model version is set " +
                  s"to '$format', however, a file in this format could not be found. Please update the " +
                  "defaultFormat field of this model version or upload the missing file."))
              }
        case _ => complete(StatusCodes.NotFound, ErrorResponse("The 'format' parameter was not specified and " +
          "the default format for this model version is not set. Please specify the 'format' query parameter."))

    // Main route body
    get {
      val gmvDir = getModelVersion(true)
      gmvDir { modelVersion => doDownload(modelVersion) }
    }

  /**
   * Post new content for the model.
   * @param formatOption content format
   * @param mvContext model version context
   * @return
   */
  def postContentRoute(formatOption: Option[String])(implicit mvContext: ModelVersionContext) =
    // First, define some helper functions.
    /**
     * Checks whether the user-provided format parameter is a Media Type and if it matches the metadata of the file.
     * We do not enforce this check, just produce some warnings.
     * @param format format query parameter
     * @param fileMetadata metadata of the file
     * @return warning or None
     */
    def checkContentType(format: String, fileMetadata: FileInfo): Option[String] =
      AkkaMediaType.parse(format) match
        case Right(mediaType) =>
          if (fileMetadata.contentType.mediaType != mediaType) {
            Some(s"The Media Type specified in the URL query parameter ('$format') does not match the " +
              s"one in the body of the request ('${fileMetadata.contentType.mediaType}').")
          } else None
        case _ =>
          Some(s"The specified format ('$format') is not a valid Media Type.")

    /**
     * Updates the model version in Mongo with the newly uploaded file's metadata.
     * @param format format query parameter
     * @param storedFile metadata to insert
     * @return Future
     */
    def updateFileMetadata(format: String, storedFile: MongoModel.StoredFile):
    Future[Option[UpdateResult]] =
      MongoModel.modelVersionCollection.updateOne(
        modelVersionFilter,
        u.set(s"formats.$format", MongoModel.StoredFile.unapply(storedFile))
      ).toFutureOption

    /**
     * If needed, updates the 'defaultFormat' field of the model version.
     * @param format format query parameter
     * @return Future
     */
    def updateDefaultFormat(format: String): Future[Option[UpdateResult]] =
      MongoModel.modelVersionCollection.updateOne(
        f.and(modelVersionFilter, f.eq("defaultFormat", BsonNull())),
        u.set("defaultFormat", format)
      ).toFutureOption

    /**
     * Performs the upload and returns status info to the client.
     * @param format format query parameter
     * @param metadata Akka HTTP metadata of the uploaded file
     * @param byteSource stream of bytes to upload
     * @return Route
     */
    def doUpload(format: String, metadata: FileInfo, byteSource: Source[ByteString, Any], overwrite: Boolean): Route =
      var warns: Seq[String] = if overwrite then Seq("Overwrote an earlier version of the content.") else Seq()
      extractRequestContext { ctx =>
        implicit val materializer: Materializer = ctx.materializer
        implicit val ec: ExecutionContextExecutor = ctx.executionContext

        checkContentType(format, metadata) foreach { w => warns = warns :+ w }

        val objectKey = s"$modelVersionPath/$format"
        val uploadFuture = FileUtils.uploadFile(Bucket.DataModel, objectKey, metadata, byteSource)
        val updateFuture = uploadFuture flatMap { storedFile =>
          for
            // Here are two updates to the same Mongo document executed in parallel. This is fine.
            um <- updateFileMetadata(format, storedFile)
            ud <- updateDefaultFormat(format)
          yield (um, ud, storedFile)
        }

        onSuccess(updateFuture) { (metaUpdateResultOption, defaultFormatUpdateResultOption, storedFile) =>
          metaUpdateResultOption match
            case Some(metaUr) if metaUr.getMatchedCount == 1 =>
              defaultFormatUpdateResultOption match
                case Some(dfUr) if dfUr.getModifiedCount == 1 =>
                  warns = warns :+ s"The default format of this model version was set to '$format'.'"
                case _ => {}

              webhookContr.dispatchWebhook(
                MongoModel.WebhookAction.ContentUpload,
                mvContext,
                DateTime.now,
                JsObject(
                  storedFileFormat.write(storedFile).asJsObject.fields.toSeq ++ Seq(
                    "format" -> JsString(format),
                    "overwrite" -> JsBoolean(overwrite),
                  ) *
                )
              )
              complete(StatusCodes.OK, SuccessResponse(
                s"Uploaded content in format '$format' for model '$modelVersionPath'. " +
                  s"Checksum: ${storedFile.md5}",
                if (warns.nonEmpty) Some(warns) else None
              ))
            case _ =>
              // This is possible if the model version was deleted halfway through the upload.
              complete(StatusCodes.NotFound, ErrorResponse(s"Could not find model version '$modelVersionPath'"))
        }
      }

    // Main route body
    post {
      if (mvContext.version == "latest")
        complete(StatusCodes.BadRequest, ErrorResponse("The 'latest' tag cannot be used for writes."))
      else
        validateFormat(formatOption) { format =>
          // Override the default 8M upload size limit. Set to infinity.
          withSizeLimit(Long.MaxValue) {
            // We must check if the content field is present here, before we do any queries.
            // We also must not extract the body twice, or Akka HTTP will be angry.
            fileUpload("content") { (metadata, byteSource) =>
              val findFuture = MongoModel.modelVersionCollection.find(modelVersionFilter).toFuture
              onSuccess(findFuture) {
                case Seq(mv) => parameters("overwrite".optional) { overwrite =>
                  val alreadyUploaded = mv.formats.getOrElse(Map()).contains(format)
                  (alreadyUploaded, overwrite) match
                    case (false, _) => doUpload(format, metadata, byteSource, false)
                    case (true, Some("1")) => doUpload(format, metadata, byteSource, true)
                    case (true, _) => complete(StatusCodes.BadRequest, ErrorResponse("Content in format " +
                      s"'$format' already exists for this model version. If you want to update it, it is " +
                      "recommended to create a new version instead. If you really want to overwrite this content, " +
                      "retry the upload with the 'overwrite=1' query parameter."))
                }
                case _ => complete(StatusCodes.NotFound, ErrorResponse("Could not find model version " +
                  s"'$modelVersionPath'"))
              }
            }
          }
        }
    }

  /**
   * Delete content from a model.
   * @param formatOption content format
   * @param mvContext model version context
   * @return
   */
  def deleteContentRoute(formatOption: Option[String])(implicit mvContext: ModelVersionContext): Route =
    def inner(format: String) =
      val updateFuture = MongoModel.modelVersionCollection.updateOne(
        f.and(modelVersionFilter, f.exists(s"formats.$format")),
        u.unset(s"formats.$format")
      ).toFutureOption

      onSuccess(updateFuture) {
        case Some(ur) if ur.getModifiedCount == 1 =>
          extractRequestContext { ctx =>
            implicit val materializer: Materializer = ctx.materializer

            val deleteFuture = S3.deleteObject(Bucket.DataModel.name, s"$modelVersionPath/$format")
              .runWith(Sink.head)
            onComplete(deleteFuture) {
              case Success(_) =>
                webhookContr.dispatchWebhook(
                  MongoModel.WebhookAction.ContentDelete,
                  mvContext,
                  DateTime.now,
                  JsObject(
                    "format" -> JsString(format),
                  )
                )
                complete(StatusCodes.OK, SuccessResponse(s"Deleted file with format '$format' " +
                  s"in model version '$modelVersionPath'."))
              // We managed to delete the entry in the DB, but not the actual file for some reason...
              // That is still fine, we may be simply cleaning up some inconsistencies. Return a success response.
              case _ => complete(StatusCodes.OK, SuccessResponse(s"Deleted metadata entry for file with format " +
                s"'$format' in model version '$modelVersionPath'."))
            }
          }
        case _ => complete(StatusCodes.NotFound, ErrorResponse(s"Could not find file with format '$format' " +
          s"in model version '$modelVersionPath'."))
      }

    delete {
      if (mvContext.version == "latest")
        complete(StatusCodes.BadRequest, ErrorResponse("The 'latest' tag cannot be used for writes."))
      else
        requireForceParam {
          validateFormat(formatOption) { inner }
        }
    }
