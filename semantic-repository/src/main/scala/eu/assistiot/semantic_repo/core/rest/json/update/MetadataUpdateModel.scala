package eu.assistiot.semantic_repo.core.rest.json.update

import eu.assistiot.semantic_repo.core.AppConfig
import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import eu.assistiot.semantic_repo.core.rest.json.MetadataClientModel
import org.bson.conversions.Bson
import org.mongodb.scala.model.Updates as u

/**
 * UpdateModel for metadata.
 * IMPORTANT: in error messages it assumes the key to the metadata in the parent object is 'metadata'.
 * @param value wrapped value of the metadata
 */
case class MetadataUpdateModel(value: MetadataClientModel) extends UpdateModel[MongoModel.Metadata]:
  private val metadataKeyRegexString = "^[\\w-]{1,100}$"
  private val metadataKeyRegex = metadataKeyRegexString.r

  override def validateInner(implicit updateMode: UpdateMode): Unit =
    def metadataValueValidator(fieldName: String): PartialFunction[Option[String], Unit] = {
      case Some(s) if s.length <= AppConfig.Limits.Metadata.maxValueLength =>
      case _ => throw new RuntimeException(s"'$fieldName' is too long")
    }

    // If inserting, check whether the number of keys does not exceed the limit.
    // For updates, this check is performed in validatePreUpdateInner
    if (updateMode == UpdateMode.Insert && value.size > AppConfig.Limits.Metadata.maxProperties)
      throw new RuntimeException("Too many metadata keys provided.")

    for ((k, v) <- value)
      // Key validation
      k match
        case metadataKeyRegex() =>
        case _ => throw new RuntimeException("Invalid metadata key provided. Metadata keys must match regex: " +
          s"'$metadataKeyRegexString'.")
      // Value validation
      v match
        case Left(scalar) =>
          val pf = unsetValidator(s"metadata.$k") orElse metadataValueValidator(s"metadata.$k")
          pf(Some(scalar))
        case Right(seq) if seq.length > AppConfig.Limits.Metadata.maxValues =>
          throw new RuntimeException(s"'metadata.$k' has too many values")
        case Right(seq) =>
          seq.zipWithIndex.foreach { (entry, ix) =>
            val pf: PartialFunction[Option[String], Unit] = {
              case Some("@unset") =>
                throw new RuntimeException(s"Array element 'metadata.$k[$ix]' cannot be unset on its own. " +
                  s"You can only unset the entire 'metadata.$k' value.")
            }
            (pf orElse metadataValueValidator(s"'metadata.$k[$ix]'"))(Some(entry))
          }

  override def validatePreUpdateInner(entity: MongoModel.Metadata): Unit =
    val allKeysCount = (value.keys ++ entity.value.keys).toSet.size
    val unsetCount = value.map({ (k, v) =>
      v match
        case Left(scalar) => scalar match
          case "@unset" =>
            entity.value.contains(k) match
              case true => 1
              // unsetting a field that doesn't exist
              case false => 0
          case _ => 0
        case _ => 0
    }).sum

    if (allKeysCount - unsetCount > AppConfig.Limits.Metadata.maxProperties)
      throw new RuntimeException("After the update, the number of metadata keys would exceed the allowed limit.")

  override def getMongoUpdates: Seq[Option[Bson]] =
    (value map { (k, v) =>
      v match
        case Left(scalar) => scalar match
          case "@unset" => Some(u.unset(s"metadata.$k"))
          case _ => Some(u.set(s"metadata.$k", Seq(scalar)))
        case Right(seq) => Some(u.set(s"metadata.$k", seq))
    }).toSeq
