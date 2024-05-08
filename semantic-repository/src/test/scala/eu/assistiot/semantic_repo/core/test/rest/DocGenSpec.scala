package eu.assistiot.semantic_repo.core.test.rest

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.*
import akka.http.scaladsl.server.*
import akka.http.scaladsl.server.directives.ContentTypeResolver
import akka.http.scaladsl.testkit.RouteTestTimeout
import akka.http.scaladsl.unmarshalling.FromResponseUnmarshaller
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.Sink
import eu.assistiot.semantic_repo.core.datamodel.*
import eu.assistiot.semantic_repo.core.rest.json.*
import eu.assistiot.semantic_repo.core.storage.Bucket
import eu.assistiot.semantic_repo.core.{AppConfig, Guardian}
import eu.assistiot.semantic_repo.core.test.ApiSpec
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.Futures.{interval, timeout}
import org.scalatest.concurrent.ScalaFutures

import java.io.File
import scala.concurrent.duration.*
import scala.reflect.ClassTag

/**
 * Documentation generation test cases, applicable to both the sandbox and model endpoints.
 */
trait DocGenSpec extends ApiSpec, ScalaFutures:
  implicit val routeTestTimeout: RouteTestTimeout = RouteTestTimeout(5.seconds)

  protected val docRoute: Route

  /**
   * Run general documentation generation tests.
   * @param mvPath /ns/model/version path, if running against the model endpoint
   * @param extraPostParams extra query params to use when submitting jobs
   */
  protected final def runDocGenTests(mvPath: Option[String], extraPostParams: Option[String]): Unit =
    for test <- docGenTests do
      test.runTest(mvPath, extraPostParams)

  /**
   * Prepare the body for uploading a new doc generation job.
   * @param fileDir name of the directory with the files to upload
   * @return
   */
  protected final def makeDocBody(fileDir: String): Multipart.FormData =
    val rootFile = new File(getClass.getResource("/docs/" + fileDir).toURI)
    val files = getFileTree(rootFile).filter(_.isFile).filter(_.getName != "EMPTY").toArray
    val parts = files.map(f => Multipart.FormData.BodyPart.fromFile(
      "content",
      ContentTypeResolver.Default.resolve(f.getName).asInstanceOf[ContentType],
      f,
    ))
    Multipart.FormData(parts*)

  private def getFileTree(f: File): LazyList[File] =
    f #:: Option(f.listFiles()).to(LazyList).flatten.flatMap(getFileTree)

  private case class DocTest[TPost: FromResponseUnmarshaller: ClassTag]
  (
    caseName: String,
    fileDir: String,
    plugin: String,
    postResponseValidator: TPost => Unit,
    completedResponseValidator: Option[DocumentationJobClientModel => Unit] = None,
    resultFiles: Set[String] = Set(),
    htmlValidator: Option[String => Unit] = None,
  ):
    // test structure:
    // - get files from dir X
    // - submit request, validate immediate response with closure 1
    // - get immediate response from doc job status, see that it's in progress
    // - wait for completion, get the ultimate response, validate it with closure 2
    // - validate the output files
    def runTest(mvPath: Option[String], extraPostParams: Option[String]): Unit =
      val baseDocGenPath = mvPath.map(_ + "/doc_gen").getOrElse("/doc_gen")
      val params = extraPostParams.getOrElse("")
      val endpointType = if mvPath.isDefined then "doc model endpoint"
      else "doc sandbox endpoint"

      s"for case '$caseName' $endpointType" should {
        var docJobHandle: Option[String] = None

        "receive the request" in {
          Post(s"$baseDocGenPath?plugin=$plugin$params", makeDocBody(fileDir)) ~> docRoute ~> check {
            val response = responseAs[TPost]
            postResponseValidator(response)
            response match
              case r: DocCompilationStartedInfo =>
                docJobHandle = Some(r.handle)
              case _ =>
          }
        }

        if completedResponseValidator.isDefined then
          "return doc job handle in the response to the POST request" in {
            docJobHandle.isDefined should be (true)
            mvPath match
              case Some(mv) =>
                docJobHandle should be (Some(mv.drop(3)))
              case _ =>
          }

          def validateProgress(dj: DocumentationJobClientModel): Unit =
            if mvPath.isEmpty then
              dj.jobId should be (docJobHandle.get)
            dj.plugin should be (plugin)
            dj.status should be (MongoModel.DocJobStatus.Started.status)
            dj.started should include ("T")
            dj.started.length should be ("yyyy-mm-ddThh:mm:ss".length)
            dj.ended should be (None)
            dj.error should be (None)

          lazy val getStatusPath = mvPath.getOrElse(s"/doc_gen/${docJobHandle.get}")

          "return that the job is in progress" in {
            Get(getStatusPath) ~> docRoute ~> check {
              status should be (StatusCodes.OK)
              val dj = if mvPath.isDefined then
                responseAs[ModelVersionClientModel].documentation.get
              else
                responseAs[DocumentationJobClientModel]
              validateProgress(dj)
            }
          }

          "return the final job result" in {
            var lastResponse: Option[DocumentationJobClientModel] = None
            eventually(timeout(10.seconds), interval(2.seconds)) {
              Get(getStatusPath) ~> docRoute ~> check {
                status should be (StatusCodes.OK)
                lastResponse = if mvPath.isDefined then
                  responseAs[ModelVersionClientModel].documentation
                else
                  Some(responseAs[DocumentationJobClientModel])

                lastResponse.get.status should not be MongoModel.DocJobStatus.Started.status
              }
            }
            completedResponseValidator.get(lastResponse.get)
          }

          // "/" should return index.html, which is always present
          val commonFilesToServe = Seq("/", "/helium/laika-helium.js", "/helium/laika-helium.css")
          lazy val baseServePath = mvPath.map(_ + "/doc").getOrElse(s"/doc_gen/${docJobHandle.get}/doc")
          lazy val baseS3OutputPath = if mvPath.isDefined then
            "model/" + mvPath.get.drop(3) + "/"
          else
            "sandbox/" + docJobHandle.get + "/"

          if resultFiles.nonEmpty then
            "save the output files" in {
              val fileList = S3
                .listBucket(Bucket.DocOutput.name, "", Some(baseS3OutputPath))
                .map({ lsr =>
                  (lsr.key.drop(baseS3OutputPath.length), lsr.size)
                })
                .runWith(Sink.seq)
                .futureValue

              for file <- fileList do
                // All files must be non-zero length
                file._2 should be > 0L

              // Check if all expected output files are present
              val filenames = fileList.map(_._1)
              for resultFile <- resultFiles do
                filenames should contain (resultFile)
            }

            val filesToServe = commonFilesToServe ++ resultFiles.map(f => "/" + f)
            for fileToServe <- filesToServe do
              s"serve the output file: '$fileToServe'" in {
                Get(s"$baseServePath$fileToServe") ~> docRoute ~> check {
                  status should be (StatusCodes.OK)
                  if fileToServe.length <= 1 || fileToServe.endsWith(".html") then
                    // HTML pages should have their content type
                    contentType should be (ContentTypes.`text/html(UTF-8)`)
                    // And they should have the proper template applied
                    val html = responseAs[String]
                    html should include ("\"helium/laika-helium.css\"")
                    html should include ("id=\"sidebar\"")
                    // No errors should be present, unless test case overrides this
                    htmlValidator.getOrElse(h => h should not include "error")(html)
                  else
                    // Don't bother checking others precisely... but make sure it's not HTML or octet-stream,
                    // this would certainly be wrong.
                    contentType should not be ContentTypes.`text/html(UTF-8)`
                    contentType should not be ContentTypes.`application/octet-stream`
                }
              }

            "redirect GET requests from '/doc' to '/doc/'" in {
              Get(s"$baseServePath") ~> docRoute ~> check {
                status should be (StatusCodes.PermanentRedirect)
                val hLoc: HttpHeader = header("Location").get
                hLoc.value() should endWith ("/doc/")
              }
            }

            // The /helium "directory" must exist (we just checked it), but the user should get 404 nonetheless.
            // Besides, S3 doesn't really know the concept of directories.
            "not list directory content" in {
              Get(s"$baseServePath/helium") ~> docRoute ~> check {
                status should be (StatusCodes.NotFound)
              }
            }
          else
            "not save the output files" in {
              val fileList = S3.listBucket(Bucket.DocOutput.name, "/", Some(baseS3OutputPath))
                .runWith(Sink.seq)
                .futureValue
              fileList shouldBe empty
            }

            for fileToServe <- commonFilesToServe do
              s"not serve the output file: '$fileToServe'" in {
                Get(s"$baseServePath$fileToServe") ~> docRoute ~> check {
                  status should be (StatusCodes.NotFound)
                }
              }
      }

  private val docGenTests = Seq(
    DocTest[ErrorResponse](
      "bad file extension",
      "bad_extension",
      "markdown",
      { e =>
        e.error should include ("file has an extension that is not allowed")
        e.error should include ("image1.mp3")
      },
    ),
    DocTest[ErrorResponse](
      "plugin mismatch",
      "simple_md",
      "rst",
      { e =>
        e.error should include ("file has an extension that is not allowed")
        e.error should include ("README.md")
      },
    ),
    DocTest[DocCompilationStartedInfo](
      "one README.md file",
      "simple_md",
      "markdown",
      { info => info.plugin should be ("markdown") },
      Some({ dj =>
        dj.status should be (MongoModel.DocJobStatus.Success.status)
        dj.error should be (None)
        dj.ended.isDefined should be (true)
      }),
      Set("index.html"),
    ),
    DocTest[DocCompilationStartedInfo](
      "no Markdown file – should return an error",
      "image_only",
      "markdown",
      { info => info.plugin should be ("markdown") },
      Some({ dj =>
        dj.status should be (MongoModel.DocJobStatus.Failed.status)
        dj.error.get should include ("No result files were generated")
        dj.ended.isDefined should be (true)
      }),
    ),
    DocTest[DocCompilationStartedInfo](
      "multiple images and Markdown source files – no compression",
      "several_files",
      "markdown",
      { info => info.plugin should be ("markdown") },
      Some({ dj =>
        dj.status should be (MongoModel.DocJobStatus.Success.status)
        dj.error should be (None)
        dj.ended.isDefined should be (true)
      }),
      Set("index.html", "image1.webp", "image2.png", "extra.html"),
    ),
  ) ++ Seq(
    // Same files, 4 different upload methods
    ("multiple files – GitHub flavor, no compression", "several_files"),
    ("multiple files – GitHub flavor, tarball (.tar)", "several_files_tar"),
    ("multiple files – GitHub flavor, compression (.tgz)", "several_files_tgz"),
    ("multiple files – GitHub flavor, compression (.tar.gz)", "several_files_tar_gz"),
  ).map(
    (caseName, fileDir) => DocTest[DocCompilationStartedInfo](
      caseName,
      fileDir,
      "gfm",
      { info => info.plugin should be ("gfm") },
      Some({ dj =>
        dj.status should be (MongoModel.DocJobStatus.Success.status)
        dj.error should be (None)
        dj.ended.isDefined should be (true)
      }),
      Set("index.html", "image1.webp", "image2.png", "extra.html"),
    )
  ) ++ Seq(
    ("nested directory, tarball (.tar)", "nested_dir_tar"),
    ("nested directory, compression (.tar.gz)", "nested_dir_tar_gz"),
  ).map(
    (caseName, fileDir) => DocTest[DocCompilationStartedInfo](
      caseName,
      fileDir,
      "markdown",
      { info => info.plugin should be ("markdown") },
      Some({ dj =>
        dj.status should be (MongoModel.DocJobStatus.Success.status)
        dj.error should be (None)
        dj.ended.isDefined should be (true)
      }),
      Set("index.html", "img/image1.webp", "img/image2.png", "extra.html"),
    )
  ) ++ Seq(
    DocTest[ErrorResponse](
      "upload exceeding size limit",
      "upload_too_big",
      "markdown",
      { e =>
        e.error should include ("Incoming entity size exceeded size limit")
        status should be (StatusCodes.PayloadTooLarge)
      },
    ),
    DocTest[ErrorResponse](
      "upload exceeding file count limit",
      "too_many_files",
      "markdown",
      { e =>
        e.error should include ("Too many uploaded files")
        e.error should include ("10 files can be uploaded")
        status should be (StatusCodes.BadRequest)
      },
    ),
    DocTest[ErrorResponse](
      "upload exceeding file count limit (after decompression)",
      "too_many_files_tgz",
      "markdown",
      { e =>
        e.error should include ("Too many uploaded files")
        e.error should include ("10 files can be uploaded")
        status should be (StatusCodes.BadRequest)
      },
    ),
    DocTest[ErrorResponse](
      "filename too long",
      "filename_too_long",
      "markdown",
      { e =>
        e.error should include ("Some uploaded files have invalid names")
        status should be (StatusCodes.BadRequest)
      },
    ),
    DocTest[ErrorResponse](
      "file structure too deep",
      "directory_too_deep",
      "markdown",
      { e =>
        e.error should include ("Some uploaded files have invalid names")
        status should be (StatusCodes.BadRequest)
      },
    ),
    DocTest[ErrorResponse](
      "ill-formed tar file",
      "bad_tar",
      "markdown",
      { e =>
        e.error should include ("Invalid archive content")
        status should be (StatusCodes.BadRequest)
      },
    ),
    DocTest[ErrorResponse](
      "ill-formed tar.gz file",
      "bad_tar_gz",
      "markdown",
      { e =>
        e.error should include ("Invalid archive content")
        status should be (StatusCodes.BadRequest)
      },
    ),
    DocTest[DocCompilationStartedInfo](
      "reStructuredText – several files",
      "rst",
      "rst",
      { info => info.plugin should be ("rst") },
      Some({ dj =>
        dj.status should be (MongoModel.DocJobStatus.Success.status)
        dj.error should be (None)
        dj.ended.isDefined should be (true)
      }),
      Set("index.html", "image.png", "api.html"),
    ),
    DocTest[DocCompilationStartedInfo](
      "Markdown – check if all unsafe features are disabled",
      "no_unsafe",
      "markdown",
      { info => info.plugin should be ("markdown") },
      Some({ dj =>
        dj.status should be (MongoModel.DocJobStatus.Success.status)
        dj.error should be (None)
        dj.ended.isDefined should be (true)
      }),
      Set("index.html"),
      Some({ html =>
        // Config is not parsed
        html should include ("Missing required reference:")
        html should include ("project.version")
        // Unsafe block directives are not allowed
        html should include ("No block directive registered with name: include")
        html should include ("No block directive registered with name: format")
      }),
    ),
  )