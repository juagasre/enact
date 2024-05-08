package eu.assistiot.semantic_repo.core.rest.json

import akka.http.scaladsl.model.DateTime
import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import eu.assistiot.semantic_repo.core.rest.json.Util.timestampToIso
import spray.json.DefaultJsonProtocol.*
import spray.json.RootJsonFormat

case class DocumentationJobClientModel(jobId: String, plugin: String, status: String, started: String,
                                       ended: Option[String], error: Option[String])

trait DocumentationJobSupport:
  implicit def docJobClientFormat: RootJsonFormat[DocumentationJobClientModel] =
    jsonFormat6(DocumentationJobClientModel.apply)

  implicit def docJobFormat:
  TransformingJsonWriter[MongoModel.DocumentationJob, DocumentationJobClientModel] =
    new TransformingJsonWriter:
      override val internalFormat = docJobClientFormat
      override def transformTo(obj: MongoModel.DocumentationJob) =
        DocumentationJobClientModel(
          obj.info.jobId.toString,
          obj.info.pluginName,
          obj.status.status,
          // The time is stored as Unix timestamp – but we return it as an ISO date string (in UTC)
          timestampToIso(obj.started),
          obj.ended.map(ended => timestampToIso(ended)),
          obj.error,
        )
