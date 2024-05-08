package eu.assistiot.semantic_repo.core.datamodel.search

/**
 * A trait for supporting implicits that provide information on filtering and sorting capabilities of a Mongo entity.
 * The entity can either be an independent document or a subdocument in Mongo.
 * See also: [[RootSearchProvider]]
 *
 * @tparam T case class of the Mongo entity
 */
trait SearchProvider[T]:
  /**
   * A field in the data model that can be filtered and sorted.
   *
   * @param apiName   name of the field in the API, relative to the current entity. Can be an empty string.
   * @param mongoName name of the field in MongoDB, relative to the current entity. Can be an empty string.
   * @param wildcard  whether a user should be allowed to filter ONLY for any child field (as with metadata)
   */
  final case class SearchableField(apiName: String, mongoName: String, wildcard: Boolean = false):
    def toKeyVal = (apiName, mongoName)

  /**
   * A sequence of fields that can be filtered and sorted.
   */
  protected lazy val searchableFields: Seq[SearchableField] = getSearchableFieldsInternal.toSeq

  /**
   * Should return a list of fields that can be filtered and sorted in this entity.
   * Can make use of the [[getChildFields]] method.
   *
   * @return
   */
  protected def getSearchableFieldsInternal: Iterable[SearchableField]

  /**
   * Transforms a child entity's searchable fields into parent-relative (by prepending appropriate prefixes).
   *
   * @param apiName   name of the child field in the API
   * @param mongoName name of the child field in MongoDB
   * @return
   */
  final protected def getChildFields[T1 : SearchProvider](apiName: String, mongoName: String):
  Iterable[SearchableField] =
    implicitly[SearchProvider[T1]].searchableFields map { field =>
      val newApiName = if (field.apiName == "") apiName else s"$apiName.${field.apiName}"
      val newMongoName = if (field.mongoName == "") mongoName else s"$mongoName.${field.mongoName}"
      SearchableField(newApiName, newMongoName, field.wildcard)
    }
