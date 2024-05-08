package eu.assistiot.semantic_repo.core.rest.resources

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern.*
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.FileInfo
import akka.stream.alpakka.file.scaladsl.Archive
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{Compression, Sink, Source}
import akka.util.{ByteString, Timeout}
import com.typesafe.scalalogging.LazyLogging
import eu.assistiot.semantic_repo.core.{AppConfig, Guardian}
import eu.assistiot.semantic_repo.core.datamodel.{DocCompilationStartedInfo, ErrorResponse, MongoModel}
import eu.assistiot.semantic_repo.core.documentation.DocManager.*
import eu.assistiot.semantic_repo.core.documentation.DocPluginRegistry
import eu.assistiot.semantic_repo.core.rest.*
import eu.assistiot.semantic_repo.core.rest.json.*
import eu.assistiot.semantic_repo.core.storage.Bucket
import org.bson.types.ObjectId

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

trait DocumentationResource extends MongoResource, LazyLogging:
  val mainSystem: ActorSystem[Guardian.Command]
  // Timeout for asking actors
  implicit val timeout: Timeout = Timeout(10.seconds)

  protected final val postBodyHelpText = "The body should contain one or more files to be processed. " +
    "The endpoint also accepts compressed .tar.gz, .tgz, and .tar files."

  /**
   * Handles all GET requests after /doc, including "", "/" and "/whatever.jpg"
   * @param jobPath path to the job in the output S3 bucket
   * @return
   */
  protected final def serveDocsRoute(jobPath: String) =
    def inner(file: String) =
      get {
        val path = Path.of("/" + jobPath, file)
        if !path.isAbsolute || !path.startsWith("/" + jobPath) then
          // Discard wonky (or malicious) paths
          complete(StatusCodes.NotFound)
        else
          FileUtils.downloadFileRoute(Bucket.DocOutput, path.toString, None)
      }

    pathEnd {
      extractUri { uri =>
        redirect(uri.withPath(uri.path ++ Uri.Path.SingleSlash), StatusCodes.PermanentRedirect)
      }
    } ~
    pathSingleSlash {
      inner("index.html")
    } ~
    path(RemainingPath) { file =>
      inner(file.toString)
    }


  protected final def postDocJobRoute(mvc: Option[AbstractModelVersionContext], overwrite: Boolean): Route =
    implicit val system: ActorSystem[Guardian.Command] = mainSystem
    implicit val ec: ExecutionContext = system.executionContext

    def startCompilation(jobId: ObjectId, plugin: String): Route =
      val info = MongoModel.DocCompilationInfo(jobId, plugin, mvc)
      val insertInfo = JobInsertInfo(overwrite)
      val compileResult = mainSystem ? (replyTo => Guardian.DocCommand(Compile(replyTo, info, insertInfo)))
      onComplete(compileResult) {
        case Success(answer) => answer match
          case CompilationStartedResponse() =>
            val handle: String = mvc.map(_.path).getOrElse(jobId.toString)
            complete(StatusCodes.OK,
              DocCompilationStartedInfo("Compilation started.", handle, info.pluginName))
          case UserError(message) =>
            complete(StatusCodes.BadRequest, ErrorResponse(message))
          case ServerError(message) =>
            complete(StatusCodes.InternalServerError, ErrorResponse(message))
        case Failure(ex) =>
          logger.error(s"Unknown doc compilation failure", ex)
          complete(StatusCodes.InternalServerError,
            ErrorResponse("Unknown documentation compilation error. Please contact the system administrator."))
      }

    def upload(plugin: String, files: Seq[(FileInfo, Source[ByteString, Any])]): Route =
      def checkFileCount(count: Int): Option[Route] =
        if count < 1 then
          Some(complete(StatusCodes.BadRequest,
            ErrorResponse("At least one file in the 'content' field must be uploaded.")))
        else if count > AppConfig.Limits.Docs.maxFilesInUpload then
          Some(complete(StatusCodes.BadRequest, ErrorResponse(s"Too many uploaded files. " +
            s"Only ${AppConfig.Limits.Docs.maxFilesInUpload} files can be uploaded to a documentation compiler.")))
        else None

      checkFileCount(files.length) match
        case Some(route) => return route
        case _ =>

      if files.exists(f => f._1.fileName.isBlank) then
        return complete(StatusCodes.BadRequest,
          ErrorResponse("All uploaded files must have their names specified."))

      val jobId = ObjectId()
      val pathBase = "/" + jobId.toString

      val inputFilesFuture: Future[Seq[(String, Path, Source[ByteString, Any])]] = if files.length == 1 &&
        (files.head._1.fileName.endsWith(".tar.gz") || files.head._1.fileName.endsWith(".tgz") ||
          files.head._1.fileName.endsWith(".tar"))
      then
        // Decompress gzip archive, if needed
        val source = if files.head._1.fileName.endsWith(".tar") then files.head._2
        else files.head._2.via(Compression.gunzip())
        // Unpack the tarball
        source
          .via(Archive.tarReader())
          .filterNot((metadata, _) => metadata.isDirectory)
          .map((metadata, stream) => (metadata.filePath, Path.of(pathBase, metadata.filePath), stream))
          .runWith(Sink.seq)
      else
        // Get uncompressed files
        val processedFiles = for (metadata, file) <- files yield
          var filename = metadata.fileName.trim
          while filename.startsWith("/") do filename = filename.drop(1)
          (filename, Path.of(pathBase, filename), file)
        Future { processedFiles }

      def checkPath(filename: String, p: Path): Boolean =
        // Limit filename length and depth
        filename.length < 100
          && filename.count(_ == '/') < 10
          && p.isAbsolute
          && p.startsWith(pathBase)

      onComplete(inputFilesFuture) {
        case Failure(_: Exception) =>
          complete(StatusCodes.BadRequest, ErrorResponse("Invalid archive content"))
        case Failure(e) => throw e
        case Success(inputFiles) =>
          // Check file count again (after decompression)
          checkFileCount(inputFiles.length) match
            case Some(route) => route
            case _ =>
              // Check filenames
              if inputFiles.exists((f, p, _) => !checkPath(f, p)) then
                complete(StatusCodes.BadRequest, ErrorResponse("Some uploaded files have invalid names"))
              else
                val filenameFilter = DocPluginRegistry.getFilenameFilter(plugin)
                val invalidExtensions = inputFiles.filterNot((filename, _, _) => filenameFilter(filename))
                if invalidExtensions.nonEmpty then
                  complete(StatusCodes.BadRequest, ErrorResponse(
                    "The following file has an extension that is not allowed " +
                      s"by this documentation compilation plugin: ${invalidExtensions.head._1}")
                  )
                else
                  // All is fine, upload the files to S3
                  val uploadFutures = for (_, path, byteSource) <- inputFiles yield
                    val sink = S3.multipartUpload(Bucket.DocSource.name, path.toString)
                    byteSource.runWith(sink)
                  val uploadFuture = Future.sequence(uploadFutures)

                  // Trigger the compilation job
                  onComplete(uploadFuture) {
                    case Success(_) => startCompilation(jobId, plugin)
                    case Failure(e) =>
                      logger.error("Failed uploading source doc files", e)
                      complete(StatusCodes.InternalServerError,
                        ErrorResponse("Could not upload the provided source files."))
                  }
      }

    // Validate the parameters and request body
    post {
      parameter("plugin".optional) {
        case Some(plugin) =>
          DocPluginRegistry.checkPlugin(plugin) match
            case DocPluginRegistry.PluginError(m) =>
              complete(StatusCodes.BadRequest, ErrorResponse(m))
            case DocPluginRegistry.PluginOk() =>
              withSizeLimit(AppConfig.Limits.Docs.maxUploadSize.toBytes) {
                handleRejections(Directives.entityRejectionHandler) {
                  fileUploadAll("content") { files =>
                    // Upload files & start the compilation job
                    upload(plugin, files)
                  }
                }
              }
        case None =>
          complete(StatusCodes.BadRequest, ErrorResponse(
            "Documentation compilation plugin name must be specified using the 'plugin' query parameter. " +
              "The list of available plugins can be found at GET /dg")
          )
      }
    }
