package eu.assistiot.semantic_repo.core.rest.json.update

import spray.json.*

import scala.util.Try

trait ValidationSupport:
  /**
   * Wraps all available UpdateModel RootJsonReaders in validation support.
   * So, if you have a wire format -> update model unmarshaller,
   * now you also have a wire format -> Try[update model] unmarshaller.
   *
   * By default assumes you want to *update* the model, not insert a new one.
   */
  implicit def validatedFormat[T <: UpdateModel[TMongo], TMongo]
  (implicit wrapped: RootJsonReader[T], um: UpdateMode = UpdateMode.Update):
  RootJsonReader[Try[T]] =
    new RootJsonReader:
      override def read(json: JsValue): Try[T] =
        val obj = wrapped.read(json)
        obj.validate(um).map(_ => obj)
