package eu.assistiot.semantic_repo.core.rest.resources

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.{EntityStreamSizeException, StatusCodes}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.*
import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller
import eu.assistiot.semantic_repo.core.Exceptions.*
import eu.assistiot.semantic_repo.core.Main
import eu.assistiot.semantic_repo.core.datamodel.*
import eu.assistiot.semantic_repo.core.datamodel.search.RootSearchProvider
import eu.assistiot.semantic_repo.core.rest.Directives.{entityRejectionHandler, validatedEntity}
import eu.assistiot.semantic_repo.core.rest.*
import eu.assistiot.semantic_repo.core.rest.json.update.UpdateModel
import org.bson.types.ObjectId
import org.mongodb.scala.*
import org.mongodb.scala.bson.conversions.Bson

import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
 * Base trait for resources interfacing with Mongo.
 */
trait MongoResource extends Resource:

  /**
   * PathMatcher for Mongo's ObjectIds.
   */
  val ObjectIdMatcher: PathMatcher1[ObjectId] =
    PathMatcher("""[\da-fA-F]{24}""".r).map(id => ObjectId(id))

  /**
   * Rejection handler for translating HTTP 404 -> 400 and HTTP 405 -> 404
   * @param message400 Error message to send to the client for 404 -> 400 rejection
   * @return rejection handler
   */
  protected def postRejectionHandler(message400: String) =
    RejectionHandler.newBuilder
      .handle {
        case MalformedRequestContentRejection(_, _) =>
          complete(StatusCodes.BadRequest, ErrorResponse("Request content was malformed."))
        case MethodRejection(_) =>
          complete(StatusCodes.NotFound, "")
      }
      .handleNotFound {
        complete(StatusCodes.BadRequest, ErrorResponse(message400))
      }
      .result()

  /**
   * Directive extracting query parameters relevant to paging, sorting, and filtering.
   * @return directive providing MongoSetParams
   */
  protected def extractMongoSetParams[T : RootSearchProvider]: Directive1[MongoSetParamsValidated[T]] =
    extract(_.request.uri.query()).tflatMap { params =>
      MongoSetParams[T](params._1).toValidated match
        case Success(validated) => provide(validated)
        case Failure(ex: ParameterValidationException) => complete(StatusCodes.BadRequest, ErrorResponse(ex.message))
        case Failure(ex) => throw ex
    }

  /**
   * Directive resolving the appropriate model version as stored in Mongo, taking into account the "latest" tag.
   * @param allowLatest Whether to allow the user to use the "latest" tag. When set to false, any requests to
   *                    the "latest" version will be rejected with a bad request status.
   * @param mvc model version context
   * @return directive with argument MongoModel.ModelVersion
   *         HTTP 404 on not found models, model versions, pointers, etc.
   */
  protected def getModelVersion(allowLatest: Boolean)(implicit mvc: AbstractModelVersionContext):
  Directive1[MongoModel.ModelVersion] =
    def inner(latest: Boolean)(implicit mvc: AbstractModelVersionContext):
    Directive1[MongoModel.ModelVersion] =
      val versionFuture = MongoModel.modelVersionCollection.find(modelVersionFilter).collect.toFuture
      onSuccess(versionFuture).flatMap {
        case Seq(modelVersion) => provide(modelVersion)
        case _ if latest => complete(StatusCodes.NotFound, ErrorResponse(s"Could not find the model version " +
          s"'$modelVersionPath', which 'latestVersion' was pointing to. Please update the pointer or create " +
          s"the missing version."))
        case _ => complete(StatusCodes.NotFound, ErrorResponse(s"Could not find model version " +
          s"'$modelVersionPath'."))
      }

    mvc.version match
      case "latest" =>
        if !allowLatest then
          complete(StatusCodes.BadRequest, 
            ErrorResponse("The use of the 'latest' tag is not allowed for this endpoint."))
        else
          val glvDir = getLatestVersion
          glvDir.flatMap { version =>
            // Make a new context.
            inner(true)(ModelVersionContext(mvc.ns, mvc.model, version))
          }
      case _ => inner(false)

  /**
   * Directive providing the latest version of a given model. Handles all errors accordingly.
   * @param mc model context
   * @return directive providing the latest version tag for this model
   */
  def getLatestVersion(implicit mc: AbstractModelContext): Directive1[String] =
    val modelFuture = MongoModel.modelCollection.find(modelFilter).collect.toFuture
    onSuccess(modelFuture).flatMap {
      case Seq(model) => model.latestVersion match
        case None => complete(StatusCodes.NotFound, ErrorResponse(s"The 'latestVersion' pointer is not set " +
          s"for model '$modelPath'."))
        case Some(v) => provide(v)
      case _ => complete(StatusCodes.NotFound, ErrorResponse(s"Model '$modelPath' not found."))
    }

  /**
   * Directive for PATCH requests on namespaces, models, and model versions.
   *
   * Performs:
   *  (1) request entity validation
   *  (2) check whether the entity is in Mongo
   *  (3) pre-update checks (within a transaction)
   *  (4) the actual update
   *
   * @param collection MongoCollection to use
   * @param filter Bson filter (see ObjectPathContext)
   * @param path path to the entity (see ObjectPathContext)
   * @param entityTypeName name of the type of the entity to display in responses (e.g., "model")
   * @param um unmarshaller for TUpdateModel
   * @tparam TMongoModel model for the entities stored in Mongo
   * @tparam TUpdateModel model for updating the entities
   * @return route
   */
  protected def patchEntity[TMongoModel : ClassTag, TUpdateModel <: UpdateModel[TMongoModel]]
  (collection: MongoCollection[TMongoModel], filter: Bson, path: String, entityTypeName: String)
  (implicit um: FromRequestUnmarshaller[Try[TUpdateModel]]): Route =
    def doUpdate(updateModel: TUpdateModel, update: Bson) =
      val transactionFuture = MongoModel.transaction({ session =>
        collection.find(session, filter).collect.flatMap {
          case Seq(entity) =>
            updateModel.validatePreUpdate(entity) match
              case Success(_) =>
                collection.updateOne(filter, update).map { ur =>
                  ur match
                    case ur if ur.getModifiedCount == 1 =>
                    case ur if ur.getMatchedCount == 1 => throw NoUpdateNeededException()
                    case _ => throw NotFoundException()
                }
              case Failure(ex: Exception) => throw PreValidationException(ex)
              case Failure(e) => throw e
          case _ => throw NotFoundException()
        }
      }, None).toFuture

      onComplete(transactionFuture) {
        case Success(_) => complete(StatusCodes.OK, SuccessResponse(s"Updated $entityTypeName '$path'."))
        case Failure(e: NotFoundException) =>
          complete(StatusCodes.NotFound, ErrorResponse(s"Could not find $entityTypeName '$path'."))
        case Failure(e: NoUpdateNeededException) =>
          complete(StatusCodes.OK,
            SuccessResponse(s"${entityTypeName.capitalize} '$path' was up-to-date, no update needed."))
        case Failure(e: PreValidationException) =>
          complete(StatusCodes.BadRequest, ErrorResponse(s"Pre-update check failed: ${e.inner.getMessage}"))
        case Failure(e) => throw e
      }

    patch {
      handleRejections(entityRejectionHandler) {
        val entityDirective = validatedEntity[TUpdateModel]
        entityDirective { updateModel =>
          updateModel.mongoUpdate match
            case Some(update) => doUpdate(updateModel, update)
            case _ => complete(StatusCodes.OK, SuccessResponse("No updates requested."))
        }
      }
    }

  /**
   * Exception handler for Mongo resources. You can wrap the entire resource in it.
   */
  protected val mongoExceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case ex: MongoTimeoutException =>
        // This looks a bit weird, but I think it's the only way to handle situations when the repo starts
        // before the database. As there's no way to catch the exception when creating the DB, we have to
        // do it on the first attempt to access it.
        // In such a situation, just shut down and let the orchestrator take care of it.
        // See: https://tinyurl.com/bwtvr7cw
        logger.error("Timeout when connecting to Mongo DB. Shutting down...")
        Main.runReaper()
        complete(
          StatusCodes.InternalServerError,
          "Cannot connect to database."
        )
      case ex: MongoWriteException => ex.getCode match {
        case 11000 => complete(
          StatusCodes.Conflict,
          ErrorResponse("An entity with the same key already exists in the repository.")
        )
        case _ =>
          logger.error("Unknown MongoDB write error", ex)
          complete(
            StatusCodes.InternalServerError,
            ErrorResponse("Unknown write error.")
          )
      }
      case ex: EntityStreamSizeException => complete(
        StatusCodes.PayloadTooLarge,
        ErrorResponse(s"Incoming entity size exceeded size limit (${ex.limit} bytes).")
      )
      case ex: Exception =>
        logger.error("HTTP 500", ex)
        complete(
          StatusCodes.InternalServerError,
          ErrorResponse("Internal server error.")
        )
    }
