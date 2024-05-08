package eu.assistiot.semantic_repo.core.rest.json

import eu.assistiot.semantic_repo.core.rest.json.update.{UpdateMode, UpdateModel}
import spray.json.*

import scala.util.{Failure, Success, Try}

/**
 * Allows unmarshalling (wire format -> case class) backend type B via a client type C.
 * @tparam B Backend data model type (usually Mongo)
 * @tparam C Client data model type
 */
trait TransformingJsonReader[B, C] extends RootJsonReader[B]:
  /**
   * Override this with e.g.: jsonFormat2(C.apply)
   */
  def internalFormat: JsonReader[C]

  /**
   * Transform client data model into a backend one.
   * @param obj Client data model instance
   * @return Backend data model instance
   */
  def transformFrom(obj: C): B

  override final def read(json: JsValue): B = transformFrom(internalFormat.read(json))

/**
 * Similar to TransformingJsonReader, but requires C (client model) to be a subclass of UpdateModel.
 * After reading the client model, it is validated. The result of the transformation is Try[B].
 * @tparam B Backend data model type (usually Mongo)
 * @tparam C Client data model type
 */
trait ValidatingTransformingJsonReader[B, C <: UpdateModel[B]](implicit updateMode: UpdateMode)
  extends RootJsonReader[Try[B]]:
  val internalFormat: RootJsonFormat[C]
  def transformFrom(obj: C): B

  override final def read(json: JsValue): Try[B] =
    val obj = internalFormat.read(json)
    obj.validate(updateMode).map(_ => transformFrom(obj))

/**
 * Allows marshalling (case class -> wire format) backend type B via a client type C.
 * @tparam B Backend data model type (usually Mongo)
 * @tparam C Client data model type
 */
trait TransformingJsonWriter[B, C] extends RootJsonWriter[B]:
  /**
   * Override this with e.g.: jsonFormat2(C.apply)
   */
  def internalFormat: JsonWriter[C]

  /**
   * Transform backend data model into a client one/
   * @param obj Backend data model instance
   * @return Client data model instance
   */
  def transformTo(obj: B): C

  override final def write(obj: B): JsValue = internalFormat.write(transformTo(obj))

/**
 * Base trait for JSON marshalling/unmarshalling that transforms
 * one (backend) data model into another (client) data model.
 * @tparam B Backend data model type (usually Mongo)
 * @tparam C Client data model type
 */
trait TransformingJsonFormat[B, C]
  extends TransformingJsonReader[B, C], TransformingJsonWriter[B, C], RootJsonFormat[B]:
  def internalFormat: JsonFormat[C]
