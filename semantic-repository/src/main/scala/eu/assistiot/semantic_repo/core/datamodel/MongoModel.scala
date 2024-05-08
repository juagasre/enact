package eu.assistiot.semantic_repo.core.datamodel

import com.mongodb.MongoClientSettings
import eu.assistiot.semantic_repo.core.AppConfig
import eu.assistiot.semantic_repo.core.rest.*
import org.bson.*
import org.bson.codecs.configuration.CodecRegistries.{fromCodecs, fromRegistries}
import org.bson.types.ObjectId
import org.mongodb.scala.bson.codecs.*
import org.mongodb.scala.bson.collection.Document
import org.mongodb.scala.{MongoClient, MongoCollection, MongoDatabase, ObservableFuture}
import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala.model.Filters as f
import org.mongodb.scala.model.{IndexOptions, Indexes}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

object MongoModel extends MongoTransactions:
  private implicit val docCodec: ImmutableDocumentCodec = ImmutableDocumentCodec()

  // Register all root-level entity classes here
  val codecRegistry = fromRegistries(
    fromCodecs(
      Namespace.codecProvider,
      Model.codecProvider,
      ModelVersion.codecProvider,
      DocumentationJob.codecProvider,
      Webhook.codecProvider,
    ),
    DEFAULT_CODEC_REGISTRY
  )

  val mongoClient: MongoClient = MongoClient(AppConfig.MongoDB.connectionString)
  val database: MongoDatabase = mongoClient
    .getDatabase("semrepo")
    .withCodecRegistry(codecRegistry)

  // Register entity collections here
  val namespaceCollection: MongoCollection[Namespace] = database.getCollection("namespace")
  val modelCollection: MongoCollection[Model] = database.getCollection("model")
  val modelVersionCollection: MongoCollection[ModelVersion] = database.getCollection("model_version")
  val docJobCollection: MongoCollection[DocumentationJob] = database.getCollection("doc_job")
  val webhookCollection: MongoCollection[Webhook] = database.getCollection("webhook")

  val allCollections = Seq(
    namespaceCollection, modelCollection, modelVersionCollection, docJobCollection, webhookCollection
  )

  /**
   * Initializes the indexes needed for the Semantic Repository.
   * Call this before serving any requests.
   *
   * @param executionContext Execution context
   * @return Future with success message. Otherwise, throws an exception.
   */
  def initializeIndexes()(implicit executionContext: ExecutionContext): Future[String] =
    val futures: Seq[Future[Seq[String]]] = Seq(
      // Namespaces by name
      namespaceCollection.createIndex(
        Indexes.ascending("name"),
        IndexOptions().unique(true),
      ).toFuture,
      // Models by ns name, name – for lookups
      modelCollection.createIndex(
        Indexes.ascending("namespaceName", "name"),
        IndexOptions().unique(true),
      ).toFuture,
      // Model versions by ns name, model name, version tag
      modelVersionCollection.createIndex(
        Indexes.ascending("namespaceName", "modelName", "version"),
        IndexOptions().unique(true),
      ).toFuture,
      // Pending documentation jobs by plugin and time submitted
      // Used by plugins to retrieve new jobs for them
      docJobCollection.createIndex(
        Indexes.ascending("pluginName", "started"),
        IndexOptions()
          .unique(false)
          .partialFilterExpression(f.eq("status", DocJobStatus.Started.status))
      ).toFuture,
      // Documentation jobs, by model version
      // Require uniqueness on jobs that are in progress
      docJobCollection.createIndex(
        Indexes.ascending("modelVersion"),
        IndexOptions()
          .unique(true)
          .partialFilterExpression(f.and(
            // We want to check if 'modelVersions' is not null, but the $ne operator
            // is not supported in partial filter expressions. So we use a type check instead.
            f.bsonType("modelVersion", BsonType.STRING),
            f.eq("status", DocJobStatus.Started.status),
          ))
      ).toFuture,
      // Documentation jobs, by whether they are marked for deletion
      // Applies only to jobs that are assigned to a specific model version
      docJobCollection.createIndex(
        Indexes.ascending("toDelete"),
        IndexOptions().unique(false),
      ).toFuture,
      // Webhooks, by action and context
      webhookCollection.createIndex(
        Indexes.ascending("action", "context_ns", "context_model", "context_version"),
        IndexOptions().unique(false),
      ).toFuture,
    )
    Future.sequence(futures) map {
      _ => "Indexes initialized"
    } recoverWith {
      case ex => throw new Exception("Could not initialize indexes", ex)
    }

  /**
   * Drops all MongoDB collections and indexes.
   * ONLY INTENDED TO BE USED IN TESTS.
   *
   * @param executionContext Execution context
   * @return Future with success message. Otherwise, throws an exception.
   */
  def dropAll()(implicit executionContext: ExecutionContext) =
    val futures = allCollections.map(_.drop().toFuture())
    Future.sequence(futures) map {
      _ => "Collections dropped"
    } recoverWith {
      case ex => throw new Exception("Could not drop collections", ex)
    }

  /**
   * Semantic Repository namespace.
   */
  object Namespace extends MongoEntity[Namespace]:
    def apply(name: String, metadata: Metadata = Metadata()): Namespace =
      Namespace(new ObjectId(), name, metadata)

    def apply(doc: Document): Namespace =
      Namespace(
        doc[BsonObjectId]("_id").getValue,
        doc[BsonString]("name").getValue,
        doc.get[BsonDocument]("metadata") map { bDoc => Metadata(Document(bDoc)) } getOrElse Metadata(),
      )

    def apply(nc: AbstractNamespaceContext): Namespace = Namespace(nc.ns)

    def unapply(entity: Namespace) =
      Document(
        "_id" -> entity._id,
        "name" -> entity.name,
        "metadata" -> Metadata.unapply(entity.metadata),
      )

  /**
   * Semantic Repository namespace.
   *
   * @param _id identifier
   * @param name name of the namespace
   * @param metadata metadata
   */
  case class Namespace(_id: ObjectId, name: String, metadata: Metadata)

  /**
   * Model (a model with multiple versions).
   */
  object Model extends MongoEntity[Model]:
    def apply(name: String, namespaceName: String, latestVersion: Option[String] = None,
              metadata: Metadata = Metadata()):
    Model =
      Model(new ObjectId(), name, namespaceName, latestVersion, metadata)

    def apply(doc: Document): Model =
      Model(
        doc[BsonObjectId]("_id").getValue,
        doc[BsonString]("name").getValue,
        doc[BsonString]("namespaceName").getValue,
        doc.get[BsonString]("latestVersion") map { _.getValue },
        doc.get[BsonDocument]("metadata") map { bDoc => Metadata(Document(bDoc)) } getOrElse Metadata(),
      )
      
    def apply(mc: AbstractModelContext): Model =
      Model(mc.model, mc.ns)

    def unapply(entity: Model) =
      Document(
        "_id" -> entity._id,
        "name" -> entity.name,
        "namespaceName" -> entity.namespaceName,
        "latestVersion" -> entity.latestVersion,
        "metadata" -> Metadata.unapply(entity.metadata),
      )

  /**
   * Model (a model with multiple versions).
   *
   * @param _id identifier
   * @param name name of the model
   * @param namespaceName name of the namespace the model is in
   * @param latestVersion pointer to the "latest" version tag of this model
   * @param metadata metadata
   */
  case class Model(_id: ObjectId, name: String, namespaceName: String, latestVersion: Option[String],
                   metadata: Metadata)

  /**
   * A specific version of a model.
   */
  object ModelVersion extends MongoEntity[ModelVersion]:
    def apply(version: String, modelName: String, namespaceName: String, defaultFormat: Option[String] = None,
              metadata: Metadata = Metadata(), docJob: Option[DocumentationJob] = None):
    ModelVersion =
      ModelVersion(new ObjectId(), version, modelName, namespaceName, Some(Map()), defaultFormat, metadata, docJob)

    def apply(doc: Document): ModelVersion =
      ModelVersion(
        doc[BsonObjectId]("_id").getValue,
        doc[BsonString]("version").getValue,
        doc[BsonString]("modelName").getValue,
        doc[BsonString]("namespaceName").getValue,
        doc.get[BsonDocument]("formats").map( { bDoc =>
          Document(bDoc).toMap map { (k, v) =>
            (k, StoredFile(Document(v.asDocument)))
          }
        } ),
        doc.get[BsonString]("defaultFormat") map { _.getValue },
        doc.get[BsonDocument]("metadata") map { bDoc => Metadata(Document(bDoc)) } getOrElse Metadata(),
        doc.get[BsonDocument]("docJob") map { bDoc => DocumentationJob(Document(bDoc)) },
      )

    def apply(mvc: AbstractModelVersionContext): ModelVersion =
      ModelVersion(mvc.version, mvc.model, mvc.ns)

    def unapply(entity: ModelVersion) =
      Document(
        "_id" -> entity._id,
        "version" -> entity.version,
        "modelName" -> entity.modelName,
        "namespaceName" -> entity.namespaceName,
        "formats" -> entity.formats.map( { formats =>
          formats.map( { (k: String, v: StoredFile) =>
            (k, StoredFile.unapply(v))
          } ).toIndexedSeq
        } ),
        "defaultFormat" -> entity.defaultFormat,
        "metadata" -> Metadata.unapply(entity.metadata),
        "docJob" -> entity.docJob.map(dj => DocumentationJob.unapply(dj)),
      )

  /**
   * A specific version of a model.
   *
   * @param _id identifier
   * @param version version tag
   * @param modelName name of the model
   * @param namespaceName name of the namespace the model is in
   * @param formats available formats of the model
   * @param defaultFormat default format to use
   * @param metadata metadata
   * @param docJob last documentation job for this model version
   */
  case class ModelVersion(_id: ObjectId, version: String, modelName: String, namespaceName: String,
                          formats: Option[Map[String, StoredFile]], defaultFormat: Option[String],
                          metadata: Metadata, docJob: Option[DocumentationJob])

  object StoredFile extends MongoEntity[StoredFile]:
    def apply(doc: Document): StoredFile =
      StoredFile(
        doc[BsonInt64]("size").getValue,
        doc[BsonString]("storageRef").getValue,
        doc[BsonString]("md5").getValue,
        doc[BsonString]("contentType").getValue,
      )

    def unapply(entity: StoredFile) =
      Document(
        "size" -> entity.size,
        "storageRef" -> entity.storageRef,
        "md5" -> entity.md5,
        "contentType" -> entity.contentType,
      )

  /**
   * Information about a file stored in S3-compatible internal storage.
   *
   * @param size file size in bytes
   * @param storageRef name of the file in the internal storage
   * @param md5 MD5 checksum of the file
   * @param contentType HTTP Content-Type header of the file
   */
  case class StoredFile(size: Long, storageRef: String, md5: String, contentType: String)

  object Metadata extends MongoEntity[Metadata]:
    def apply(doc: Document): Metadata =
      Metadata(
        doc.toMap map { (k, v) =>
          (k, Seq() ++ v.asArray.asScala.map(_.asString.getValue))
        }
      )

    def unapply(entity: Metadata) =
      Document(entity.value)

  case class Metadata(value: Map[String, Seq[String]] = Map())

  object DocumentationJob extends MongoEntity[DocumentationJob]:
    override def apply(doc: Document): DocumentationJob =
      DocumentationJob(
        DocCompilationInfo(
          doc[BsonObjectId]("_id").getValue,
          doc[BsonString]("pluginName").getValue,
          doc.get[BsonString]("modelVersion") map { path =>
            ObjectPathContext.modelVersionFromPath(path.getValue)
          },
        ),
        DocJobStatus.valueOf(doc[BsonString]("status").getValue),
        doc[BsonInt64]("started").getValue,
        doc.get[BsonInt64]("ended") map { _.getValue },
        doc.get[BsonString]("error") map { _.getValue },
        doc[BsonBoolean]("toDelete").getValue,
      )

    override def unapply(e: DocumentationJob) =
      Document(
        "_id" -> e.info.jobId,
        "pluginName" -> e.info.pluginName,
        "modelVersion" -> e.info.modelVersion.map(_.path),
        "status" -> e.status.status,
        "started" -> e.started,
        "ended" -> e.ended,
        "error" -> e.error,
        "toDelete" -> e.toDelete,
      )

  /**
   * A documentation compilation job record.
   * This case class does not have an _id field. Instead the ID is in DocCompilationInfo.
   * @param info information about the job needed for the doc compiler
   * @param status current status
   * @param started the time this job was first recorded (Unix timestamp)
   * @param ended the time this job was finished (successfully or not) (Unix timestamp)
   * @param error error message (in case the job failed) to display to the user
   * @param toDelete only applicable to model jobs – whether to delete this job in the next cleanup
   */
  case class DocumentationJob(info: DocCompilationInfo, status: DocJobStatus, started: Long,
                              ended: Option[Long] = None, error: Option[String] = None, toDelete: Boolean = false)

  /**
   * Documentation compilation parameters to be passed to the doc compiler plugin.
   * @param pluginName name of the plugin / its key in the API
   * @param jobId job identifier
   * @param modelVersion If the job is assigned to a specific model version, it path context
   *                     (ns/model/version). None for jobs executing in sandbox mode (POST /dg endpoint).
   */
  case class DocCompilationInfo(jobId: ObjectId, pluginName: String, modelVersion: Option[AbstractModelVersionContext]):
    /**
     * @return output directory in which to save compiled documentation
     */
    def getOutputDir: String = modelVersion
      .map(mvc => "model/" + mvc.path)
      .getOrElse("sandbox/" + jobId)

  /**
   * Current job status.
   * The 'status' val corresponds directly to the name of the enum value – valueOf method can be used here.
   */
  enum DocJobStatus(val status: String):
    case Started extends DocJobStatus("Started")
    case Failed extends DocJobStatus("Failed")
    case Success extends DocJobStatus("Success")

  object Webhook extends MongoEntity[Webhook]:
    val allowedActions = WebhookAction.values
    val keyToAction = Map(allowedActions.map(action => (action.key, action)) *)

    def apply(action: WebhookAction, context: ObjectPathContext, callback: String): Webhook =
      Webhook(ObjectId(), action, context, callback)

    override def apply(doc: Document): Webhook =
      Webhook(
        doc[BsonObjectId]("_id").getValue,
        keyToAction(doc[BsonString]("action").getValue),
        ObjectPathContext.fromParts(
          doc.get[BsonString]("context_ns") map { _.getValue },
          doc.get[BsonString]("context_model") map { _.getValue },
          doc.get[BsonString]("context_version") map { _.getValue },
        ),
        doc[BsonString]("callback").getValue,
      )

    override def unapply(e: Webhook) =
      Document(
        "_id" -> e._id,
        "action" -> e.action.key,
        "context_ns" -> e.context.getNamespace,
        "context_model" -> e.context.getModel,
        "context_version" -> e.context.getModelVersion,
        "callback" -> e.callback,
      )

  /**
   * A registered webhook.
   * @param _id identifier, also used by clients to identify the webhook
   * @param action action to which the hooks is registered
   * @param context object path context to which the webhook should apply
   * @param callback callback URI
   */
  case class Webhook(_id: ObjectId, action: WebhookAction, context: ObjectPathContext, callback: String)

  /**
   * Available webhook actions.
   */
  enum WebhookAction(val key: String):
    case ContentUpload extends WebhookAction("content_upload")
    case ContentDelete extends WebhookAction("content_delete")
    case ModelDelete extends WebhookAction("model_delete")
    case ModelVersionDelete extends WebhookAction("model_version_delete")
    case NamespaceDelete extends WebhookAction("namespace_delete")
