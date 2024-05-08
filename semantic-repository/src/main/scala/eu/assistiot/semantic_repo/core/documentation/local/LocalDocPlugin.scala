package eu.assistiot.semantic_repo.core.documentation.local

import akka.Done
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.ContentType
import akka.http.scaladsl.server.directives.ContentTypeResolver
import akka.stream.alpakka.s3.MultipartUploadResult
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{Sink, StreamConverters}
import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import eu.assistiot.semantic_repo.core.documentation.DocPlugin
import eu.assistiot.semantic_repo.core.storage.Bucket
import laika.ast.Path
import laika.io.api.TreeTransformer
import laika.io.model.{InputTree, InputTreeBuilder, RenderedTreeRoot, StringTreeOutput}

import java.io.{ByteArrayInputStream, InputStream}
import scala.concurrent.{ExecutionContext, Future}

/**
 * Trait for documentation plugins that run within the same JVM as the Semantic Repository.
 * Assumes the plugin to be based on the Laika library.
 */
trait LocalDocPlugin extends DocPlugin:

  def allowedExtensions: Set[String] = Set(
    // Only allow images – no CSS, HTML, or conf files
    // See: https://developer.mozilla.org/en-US/docs/Web/Media/Formats/Image_types
    "jpg", "png", "svg", "jpeg", "bmp", "webp", "gif"
  )

  /**
   * @return Laika transformer for this plugin
   */
  protected def getTransformer: Resource[IO, TreeTransformer[IO]]

  private lazy val transformerAlloc = getTransformer.allocated
  private lazy val (transformer: TreeTransformer[IO], releaseF: IO[Unit]) = transformerAlloc.unsafeRunSync()

  override def release(): Unit =
    releaseF.unsafeRunSync()

  override def compile(info: MongoModel.DocCompilationInfo)
                      (implicit sys: ActorSystem[Nothing], ec: ExecutionContext):
  Future[Either[Done, CompilationError]] =
    val compilationFuture = getInput(info) flatMap { input =>
      transformer
        .fromInput(input)
        .toOutput(StringTreeOutput)
        .transform
        .unsafeToFuture()
    }

    compilationFuture flatMap { treeRoot =>
      if treeRoot.allDocuments.isEmpty then
        Future {
          Right(CompilationError("No result files were generated."))
        }
      else
        try {
          saveOutput(treeRoot, info) map (_ => Left(Done))
        } catch {
          case _: Throwable => Future {
            // Don't give the user all details
            Right(CompilationError("Could not save the resulting documents."))
          }
        }
    } recover {
      case e: Exception =>
        Right(CompilationError(e.getMessage))
    }

  /**
   * Builds a file input tree for Laika to process, from the input files stored in S3.
   * @param info doc compilation info
   * @param sys actor system
   * @param ec execution context
   * @return
   */
  private def getInput(info: MongoModel.DocCompilationInfo)
                      (implicit sys: ActorSystem[Nothing], ec: ExecutionContext):
  Future[InputTreeBuilder[IO]] =
    val listSource = S3.listBucket(Bucket.DocSource.name, "", Some(info.jobId.toString + "/"))
    val s3InputsFuture = listSource
      .filter(item => isFilenameAllowed(item.key))
      .flatMapConcat(lsResult => {
        S3.download(Bucket.DocSource.name, lsResult.key)
          .collect({
            case Some((is, _)) => (
              is.runWith(StreamConverters.asInputStream()),
              // Key of the object without the prefix
              lsResult.key.drop(info.jobId.toString.length + 1),
            )
          })
      })
      .runWith(Sink.seq)

    s3InputsFuture map { s3Inputs =>
      var inputs = InputTree[IO]
      for (is, key) <- s3Inputs do
        inputs = inputs.addInputStream(IO(is), Path.parse(key))
      inputs
    }


  /**
   * Saves the rendered output files to the output S3 bucket.
   * @param treeRoot file tree to save
   * @param info doc compilation info
   * @param sys actor system
   * @param ec execution context
   * @return
   */
  private def saveOutput(treeRoot: RenderedTreeRoot[IO], info: MongoModel.DocCompilationInfo)
                        (implicit sys: ActorSystem[Nothing], ec: ExecutionContext):
  Future[Seq[MultipartUploadResult]] =
    val staticResources = for sDoc <- treeRoot.staticDocuments yield (
      sDoc.path.toString,
      // It's a mess.
      // There is no up-to-date FS2 -> Akka Streams converter at the moment, so here is this.
      sDoc.input.through(fs2.io.toInputStream).compile.resource.lastOrError.allocated.unsafeRunSync()._1,
    )
    val compiledResources = for doc <- treeRoot.allDocuments yield (
      doc.path.toString,
      new ByteArrayInputStream(doc.content.getBytes),
    )
    val allResources: Seq[(String, InputStream)] =
      staticResources ++ compiledResources

    val saveFutures: Seq[Future[MultipartUploadResult]] =
      for (rPath, is) <- allResources yield
        val sink = S3.multipartUpload(
          Bucket.DocOutput.name,
          info.getOutputDir + rPath,
          ContentTypeResolver.Default.resolve(rPath).asInstanceOf[ContentType],
        )
        StreamConverters.fromInputStream(() => is)
          .runWith(sink)

    Future.sequence(saveFutures)
