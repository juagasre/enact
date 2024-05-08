package eu.assistiot.semantic_repo.core.rest

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.*
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller
import eu.assistiot.semantic_repo.core.datamodel.ErrorResponse
import eu.assistiot.semantic_repo.core.rest.json.JsonSupport

import scala.util.{Failure, Success, Try}

/**
 * General-purpose Akka HTTP directives.
 */
object Directives extends JsonSupport:
  /**
   * To be used with the POST/PATCH entity-related directives.
   * Catches ALL rejections and returns an HTTP 400 error.
   */
  val entityRejectionHandler =
    RejectionHandler.newBuilder
      .handle {
        case RequestEntityExpectedRejection =>
          complete(StatusCodes.BadRequest, ErrorResponse("Request body is required."))
        case _ =>
          complete(StatusCodes.BadRequest, ErrorResponse("Unsupported content type of request body or " +
            "malformed content."))
      }
      .result()

  /**
   * Provides a validated POST/PATCH JSON entity.
   * @param um unmarshaller for Try[T]
   * @tparam T Type of the model to be unmarshalled.
   *           There must be an unmarshaller for Try[T], see UpdateModel and ValidationSupport traits.
   * @return directive with one parameter of type T
   *         HTTP 400 on semantically invalid content
   */
  def validatedEntity[T](implicit um: FromRequestUnmarshaller[Try[T]]): Directive1[T] =
    entity(um).flatMap { _ match
      case Failure(e) => complete(StatusCodes.BadRequest, ErrorResponse(s"Error validating the body of the " +
        s"request: ${e.getMessage}"))
      case Success(obj) =>
        provide(obj)
    }

  /**
   * Provides a validated POST/PATCH JSON entity or a default value, if there was no body.
   * @param default default value to use for the entity if the request body was empty
   * @param um unmarshaller for Try[T]
   * @tparam T Type of the model to be unmarshalled.
   *           There must be an unmarshaller for Try[T], see UpdateModel and ValidationSupport traits.
   * @return directive with one parameter of type T
   *         HTTP 400 on semantically invalid content
   */
  def validatedEntityOrEmpty[T](default: T)(implicit um: FromRequestUnmarshaller[Try[T]]): Directive1[T] =
    requestEntityEmpty.tflatMap { _ => provide(default) } | validatedEntity

  /**
   * Directive that requires the user to use the 'force' parameter set to '1'. Used for deleting entities.
   * @return
   */
  def requireForceParam: Directive0 =
    Directive { inner =>
      parameters("force".optional) { force => force.flatMap(_.toIntOption) match
        case Some(1) => inner(())
        case _ => complete(StatusCodes.BadRequest,
          ErrorResponse("To really perform this action, the 'force' parameter must be set to '1'."))
      }
    }
