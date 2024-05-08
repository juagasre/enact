package eu.assistiot.semantic_repo.core.rest.json

import eu.assistiot.semantic_repo.core.documentation.DocPlugin
import spray.json.DefaultJsonProtocol.*
import spray.json.RootJsonFormat

case class DocPluginClientModel(description: String, allowedFileExtensions: Set[String])
case class DocMetaClientModel(enabledPlugins: Map[String, DocPluginClientModel])

trait DocPluginSupport:
  implicit def docPluginClientFormat: RootJsonFormat[DocPluginClientModel] =
    jsonFormat2(DocPluginClientModel.apply)

  implicit def docMetaClientFormat: RootJsonFormat[DocMetaClientModel] =
    jsonFormat1(DocMetaClientModel.apply)

  implicit def docPluginFormat: TransformingJsonWriter[Iterable[DocPlugin], DocMetaClientModel] =
    new TransformingJsonWriter:
      override val internalFormat = docMetaClientFormat
      override def transformTo(seq: Iterable[DocPlugin]) =
        DocMetaClientModel(
          seq.map(obj =>
            (obj.name, DocPluginClientModel(obj.description, obj.allowedExtensions))
          ).toMap
        )
