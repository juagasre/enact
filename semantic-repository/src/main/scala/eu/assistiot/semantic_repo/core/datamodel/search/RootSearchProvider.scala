package eu.assistiot.semantic_repo.core.datamodel.search

/**
 * A [[SearchProvider]] for a MongoDB entity that exists as an independent (root) document.
 *
 * @tparam T case class of the entity
 */
trait RootSearchProvider[T] extends SearchProvider[T] :
  private lazy val searchableFieldLookup: Map[String, String] =
    searchableFields.filter(!_.wildcard).map(_.toKeyVal).toMap
  private lazy val wildcardFieldLookup: Map[String, String] =
    searchableFields.filter(_.wildcard).map(_.toKeyVal).toMap

  /**
   * Returns the name of the Mongo field that corresponds to an API field (as specified by the user).
   * @param apiFieldName name of the field in the API
   * @return Some(mongo field name) or None if the field is unknown or invalid
   */
  final def getMongoField(apiFieldName: String): Option[String] =
  // 1. check if the field is in the standard lookup
    searchableFieldLookup.get(apiFieldName) match
      case Some(field) => Some(field)
      case _ =>
        // 2. if not, try stripping the last field name part and check the wildcard field lookup
        val ix = apiFieldName.lastIndexOf('.')
        if (ix == -1)
          return None

        val baseField = apiFieldName.slice(0, ix)
        wildcardFieldLookup.get(baseField) match
          case Some(mongoField) =>
            // in wildcards, the child field's api name == mongo name
            val childField = apiFieldName.drop(ix + 1)
            if childField.isEmpty then
              None
            else
              Some(s"$mongoField.$childField")
          case _ =>
            None
