package eu.assistiot.semantic_repo.core.rest.json

import com.fasterxml.jackson.annotation.JsonIgnore

/**
 * To be implemented by client model case classes that provide support for metadata.
 */
trait HasMetadata:
  val metadata: Option[MetadataClientModel]

trait HasName:
  /**
   * Base name for this entity, used in searching & indexing.
   *
   * This trait is mostly only useful in tests.
   * @return name
   */
  @JsonIgnore
  def getName: String

/**
 * To be implemented by client model case classes that have a set of results in them.
 * @tparam T type of the child entity
 */
trait HasSet[+T]:
  @JsonIgnore
  def getSet: Option[SetClientModel[T]]

