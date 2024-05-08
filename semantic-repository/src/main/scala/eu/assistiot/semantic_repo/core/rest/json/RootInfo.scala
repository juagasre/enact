package eu.assistiot.semantic_repo.core.rest.json

import eu.assistiot.semantic_repo.core.datamodel.{MongoModel, RootInfo}
import spray.json.DefaultJsonProtocol.*
import spray.json.RootJsonFormat

/**
 * Client model for the root (/) endpoint.
 * @param namespaces Set of namespaces in the Semantic Repository
 */
final case class RootInfoClientModel(namespaces: Option[SetClientModel[NamespaceClientModel]])
  extends HasSet[NamespaceClientModel]:
  override def getSet = namespaces

trait RootInfoSupport extends NamespaceSupport:
  implicit val rootInfoClientFormat: RootJsonFormat[RootInfoClientModel] =
    jsonFormat1(RootInfoClientModel.apply)

  implicit val rootInfoFormat: TransformingJsonWriter[RootInfo, RootInfoClientModel] =
    new TransformingJsonWriter:
      override val internalFormat = rootInfoClientFormat
      override def transformTo(obj: RootInfo) = RootInfoClientModel(
        // Have to provide class tags explicitly, otherwise dotty gets lost :(
        obj.namespaces map setTransformingWriter[MongoModel.Namespace, NamespaceClientModel].transformTo
      )
