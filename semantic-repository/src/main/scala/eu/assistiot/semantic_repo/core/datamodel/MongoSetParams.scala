package eu.assistiot.semantic_repo.core.datamodel

import akka.http.scaladsl.model.Uri
import eu.assistiot.semantic_repo.core.AppConfig
import eu.assistiot.semantic_repo.core.Exceptions.ParameterValidationException
import eu.assistiot.semantic_repo.core.datamodel.search.RootSearchProvider

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/**
 * Set of parameters for paging, sorting, and filtering a MongoSet, as extracted from the request.
 * Needs to be validated before being used.
 *
 * @param params URI query parameters
 * @tparam T Client model class for the documents in this set.
 */
case class MongoSetParams[T : RootSearchProvider](params: Uri.Query):
  def toValidated: Try[MongoSetParamsValidated[T]] =
    // Parse paging parameters
    val pageSize = params.get("page_size")
    val nPageSize = pageSize.flatMap(_.toIntOption)

    (pageSize, nPageSize) match
      case (Some(_), Some(ps)) =>
        if ps < 1 then
          return Failure(ParameterValidationException("Page size cannot be smaller than 1."))
        if ps > AppConfig.Limits.maxPageSize then
          return Failure(ParameterValidationException(s"Maximum allowed page size is ${AppConfig.Limits.maxPageSize}."))
      case (Some(_), None) =>
        return Failure(ParameterValidationException("'page_size' parameter must be an integer."))
      case _ =>

    val page = params.get("page")
    val nPage = page.flatMap(_.toIntOption)

    (page, nPage) match
      case (Some(_), Some(p)) =>
        if p < 1 then
          return Failure(ParameterValidationException("Page number cannot be lower than 1."))
      case (Some(_), None) =>
        return Failure(ParameterValidationException("'page' parameter must be an integer."))
      case _ =>

    val searchProvider = implicitly[RootSearchProvider[T]]

    // Parse sorting parameters
    val sortByApi = params.get("sort_by").map(_.trim)
    val sortByMongo = sortByApi.flatMap(searchProvider.getMongoField)

    if sortByApi.isDefined && sortByMongo.isEmpty then
      return Failure(ParameterValidationException(s"Invalid sorting key: '${sortByApi.get}'."))

    val order = params.get("order") match
      case Some(o) => Some(o)
      case None if sortByMongo.isDefined => Some("ascending")
      case _ => None

    order match
      case Some(s) if s != "ascending" && s != "descending" =>
        return Failure(ParameterValidationException("Sort order must be either 'ascending' or 'descending'."))
      case _ =>

    val sortParams = if sortByMongo.isDefined then Some((order.get, sortByMongo.get)) else None

    // Parse filters
    val filters = new mutable.ListBuffer[(String, String)]()
    for (k, v) <- params do
      if k.startsWith("f.") then
        val filterField = k.drop(2)
        searchProvider.getMongoField(filterField) match
          case Some(mongoField) => filters.append((mongoField, v))
          case _ => return Failure(ParameterValidationException(s"Invalid filtering key: '$k'"))

    Success(MongoSetParamsValidated(
      nPage getOrElse 1,
      nPageSize getOrElse AppConfig.Limits.defaultPageSize,
      filters,
      sortParams,
    ))

/**
 * A validated set of params for browsing collections.
 * @param page page number (1-based)
 * @param pageSize page size
 * @param filters iterable of (Mongo field name, value), a list of AND conditions
 * @param sortParams optional: (ascending/descending, field to sort by)
 * @tparam T client model for the document type
 */
case class MongoSetParamsValidated[T : RootSearchProvider]
(page: Int, pageSize: Int, filters: Iterable[(String, String)], sortParams: Option[(String, String)])
