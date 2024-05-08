package eu.assistiot.semantic_repo.core

object Exceptions:
  /**
   * For situations when a user tries to delete a non-empty entity without explicitly asking for a cascade delete.
   * @param what the thing that is present
   */
  case class NonEmptyEntityException(what: String) extends Exception

  /**
   * For precondition check failed – a dependency does not exist.
   *
   * E.g., when creating a model X in namespace Y, where Y does not exist.
   */
  case class NotFoundException() extends Exception

  /**
   * For failed pre-update validations. Only used for PATCH and some DELETE requests.
   */
  case class PreValidationException(inner: Exception) extends Exception

  /**
   * For PATCH requests where no updates were required.
   */
  case class NoUpdateNeededException() extends Exception

  /**
   * For invalid URL parameter values.
   * @param message message
   */
  case class ParameterValidationException(message: String) extends Exception
