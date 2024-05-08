package eu.assistiot.semantic_repo.core.documentation

import akka.Done
import akka.actor.typed.ActorSystem
import cats.effect.IO
import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import laika.io.model.RenderedTreeRoot

import scala.concurrent.{ExecutionContext, Future}

/**
 * Abstract plugin that can carry out documentation compilation jobs.
 */
trait DocPlugin:
  // TODO: #57F job runtime limits
  /**
   * Compilation error with a message that can be presented to the user.
   * @param message a meaningful message
   */
  case class CompilationError(message: String)

  /**
   * Name of the plugin / its key in the API.
   */
  def name: String

  /**
   * Human-readable description of the plugin.
   */
  def description: String

  /**
   * A set of allowed file extensions.
   * Only files with these extensions will be passed to the plugin.
   */
  def allowedExtensions: Set[String]

  /**
   * Whether a given filename is allowed to be uploaded to this plugin.
   * @param filename filename (may contain slashes)
   * @return bool
   */
  def isFilenameAllowed(filename: String): Boolean =
    filename.contains('.') &&
      allowedExtensions.contains(
        filename.drop(filename.lastIndexOf('.') + 1)
      )

  /**
   * Request the compilation of documentation job.
   * @param info parameters to the compiler
   * @param sys actor system
   * @param ec execution context
   * @return either Done (indicating success) or an error
   */
  def compile(info: MongoModel.DocCompilationInfo)
             (implicit sys: ActorSystem[Nothing], ec: ExecutionContext): Future[Either[Done, CompilationError]]

  /**
   * Release the resources held by this doc plugin.
   */
  def release(): Unit
