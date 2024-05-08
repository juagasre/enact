package eu.assistiot.semantic_repo.core.rest.json

import eu.assistiot.semantic_repo.core.datamodel.{MongoModel, MongoSet}
import spray.json.DefaultJsonProtocol.*
import spray.json.*

/**
 * Client model for collections (sets) of things.
 *
 * @param items list of items in the current view
 * @param totalCount total count of items in the collection, matching the given filter
 * @param inViewCount count of the documents in the current view (page)
 * @param page number of the current page (1-based)
 * @param pageSize maximum number of documents to display on the page
 */
final case class SetClientModel[+T](items: Iterable[T], totalCount: Int, inViewCount: Int, page: Int, pageSize: Int)

trait SetSupport:
  implicit def setClientFormat[T](implicit formatter: JsonFormat[T]): RootJsonFormat[SetClientModel[T]] =
    jsonFormat5(SetClientModel[T].apply)

  implicit def setTransformingWriter[B, C](implicit f1: TransformingJsonWriter[B, C], f2: JsonFormat[C]):
  TransformingJsonWriter[MongoSet[B], SetClientModel[C]] =
    new TransformingJsonWriter:
      override val internalFormat = setClientFormat[C]
      override def transformTo(obj: MongoSet[B]) =
        SetClientModel(
          obj.items map f1.transformTo,
          obj.totalCount,
          obj.inViewCount,
          obj.page,
          obj.pageSize,
        )
