package eu.assistiot.semantic_repo.core.rest.json

import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import spray.json.DefaultJsonProtocol.*
import spray.json.RootJsonFormat

/**
 * Client model for all files stored in the Semantic Repository.
 * @param size size in bytes
 * @param md5 MD5 checksum of the file
 * @param contentType HTTP Content-Type of the file
 */
case class StoredFileClientModel(size: Long, md5: String, contentType: String)

trait StoredFileSupport:
  implicit def storedFileClientFormat: RootJsonFormat[StoredFileClientModel] =
    jsonFormat3(StoredFileClientModel.apply)

  implicit def storedFileFormat:
  TransformingJsonWriter[MongoModel.StoredFile, StoredFileClientModel] =
    new TransformingJsonWriter:
      override val internalFormat = storedFileClientFormat
      override def transformTo(obj: MongoModel.StoredFile) =
        StoredFileClientModel(
          obj.size,
          obj.md5,
          obj.contentType,
        )
