package eu.assistiot.semantic_repo.core.rest.json.update

import com.fasterxml.jackson.annotation.JsonIgnore
import eu.assistiot.semantic_repo.core.rest.resources.ContentResource
import org.bson.conversions.Bson
import org.mongodb.scala.model.Updates as u

import scala.util.{Failure, Success, Try}

/**
 * Indicates whether in the current operation the backend model is inserted (created) or just updated.
 * This changes the behavior of UpdateModel validation.
 */
enum UpdateMode:
  case Update, Insert

/**
 * Trait for case classes used for update operations.
 * @tparam TMongoModel type of the target MongoDB model of the update operation
 */
trait UpdateModel[TMongoModel]:

  /**
   * Asserts if the "@unset" special value is used in the appropriate context (not during an insert)
   * @param fieldName name of the field in the CLIENT model
   * @param updateMode either Update or Insert. On Insert, unsetting values is disallowed.
   * @return partial function
   */
  protected final def unsetValidator(fieldName: String)(implicit updateMode: UpdateMode):
  PartialFunction[Option[String], Unit] = {
    case Some("@unset") =>
      updateMode match
        case UpdateMode.Insert => throw new RuntimeException(s"'$fieldName' cannot be unset during creation")
        case _ =>
  }

  /**
   * Shorthand for validating a "standard" property that can be set, unset, or unchanged.
   * @param optionValue the value as provided in the request
   * @param fieldName name of the field in the CLIENT model
   * @param alsoValid partial function covering cases that are also valid (besides unset and unchanged). Example:
   *                  case someRegex() => {}
   * @param updateMode either Update or Insert. On Insert, unsetting values is disallowed.
   */
  protected final def validEmptyOrUnset(optionValue: Option[String], fieldName: String,
                                        alsoValid: PartialFunction[Option[String], Unit] = PartialFunction.empty)
                                       (implicit updateMode: UpdateMode): Unit =
    val pfNone: PartialFunction[Option[String], Unit] = { case None => }
    val pfInvalidFormat: PartialFunction[Option[String], Unit] = {
      case Some(_) => throw new RuntimeException(s"'$fieldName' is in invalid format")
    }
    (pfNone orElse unsetValidator(fieldName) orElse alsoValid orElse pfInvalidFormat)(optionValue)

  /**
   * Shorthand for setting/unsetting a simple Mongo field.
   * @param mongoField name of the field in Mongo
   * @param optionValue value as provided by the user
   * @return None if no update, Some(Bson) otherwise
   */
  protected final def propertySetUnset(mongoField: String, optionValue: Option[String]): Option[Bson] =
    optionValue match
      case None => None
      case Some("@unset") => Some(u.unset(mongoField))
      case Some(value) => Some(u.set(mongoField, value))

  /**
   * Validate the content of this client model.
   * @param updateMode update mode to use in validation
   * @return Failure(ex: RuntimeException) on failure, Success(Unit) otherwise.
   */
  final def validate(implicit updateMode: UpdateMode): Try[Unit] =
    try {
      validateInner
    } catch {
      case e: Exception => return Failure(e)
    }
    Success(())

  /**
   * Inner implementation of validate(). Should be a series of assertions, which may throw an Exception.
   * @param updateMode update mode to use in validation
   */
  protected def validateInner(implicit updateMode: UpdateMode): Unit

  /**
   * Validate the stored entity BEFORE any actual updates are performed. Only applicable to UpdateMode.Update.
   *
   * This can be used to carry out complex checks, such as ensuring that the total number of metadata keys after the
   * update won't exceed the limit.
   *
   * @param entity the stored entity to be validated against this update
   * @return Failure(ex: RuntimeException) on failure, Success(Unit) otherwise.
   */
  final def validatePreUpdate(entity: TMongoModel): Try[Unit] =
    try {
      validatePreUpdateInner(entity)
    } catch {
      case e: Exception => return Failure(e)
    }
    Success(())

  /**
   * Inner implementation of validatePreUpdate(). Should be a series of assertions, which may throw an Exception.
   * @param entity the stored entity to be validated against this update
   */
  protected def validatePreUpdateInner(entity: TMongoModel): Unit

  /**
   * Creates MongoDB update instructions from this client model.
   * @return None on no updates, Some[Bson] otherwise
   */
  final def mongoUpdate: Option[Bson] =
    val updates = getMongoUpdates.flatten
    updates.nonEmpty match
      case true => Some(u.combine(updates*))
      case false => None

  /**
   * Returns a list of Bson update operations. Implement this in subclasses.
   * @return
   */
  @JsonIgnore
  protected def getMongoUpdates: Seq[Option[Bson]]
