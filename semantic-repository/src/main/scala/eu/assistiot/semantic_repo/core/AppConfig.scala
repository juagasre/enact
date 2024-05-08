package eu.assistiot.semantic_repo.core

import com.typesafe.config.{Config, ConfigFactory}

import scala.jdk.DurationConverters.*

/**
 * Global configuration object for easier and type-safe access to SemRepo's settings.
 */
object AppConfig:
  private var config = getEnvConfig.withFallback(ConfigFactory.load())

  private def getEnvConfig: Config =
    val env = System.getenv("REPO_EXTRA_CONFIG")
    if env == null then ConfigFactory.empty()
    else ConfigFactory.parseString(env)

  /**
   * ONLY FOR USE IN TESTS. Sets a new base config to use by the Semantic Repository.
   * @param newConfig new config
   */
  def setNewConfig(newConfig: com.typesafe.config.Config): Unit =
    config = newConfig

  /**
   * @return base config
   */
  def getConfig = config

  object MongoDB:
    val connectionString = config.getString("semrepo.mongodb.connection-string")

  object OpenApi:
    val host = config.getString("semrepo.openapi.host")

  object Http:
    val host = config.getString("semrepo.http.host")
    val port = config.getInt("semrepo.http.port")

  object Limits:
    val defaultPageSize = config.getInt("semrepo.limits.default-page-size")
    val maxPageSize = config.getInt("semrepo.limits.max-page-size")

    object Metadata:
      val maxProperties = config.getInt("semrepo.limits.metadata.max-properties")
      val maxValues = config.getInt("semrepo.limits.metadata.max-values")
      val maxValueLength = config.getInt("semrepo.limits.metadata.max-value-length")

    object Docs:
      val maxUploadSize = config.getMemorySize("semrepo.limits.docs.max-upload-size")
      val maxFilesInUpload = config.getInt("semrepo.limits.docs.max-files-in-upload")
      val sandboxExpiry = config.getDuration("semrepo.limits.docs.sandbox-expiry").toScala
      val jobExecutionTime = config.getDuration("semrepo.limits.docs.job-execution-time").toScala

    object Webhook:
      val maxCallbackLength = config.getInt("semrepo.limits.webhook.max-callback-length")

  object Scheduled:
    val docJobCleanup = config.getDuration("semrepo.scheduled.doc-job-cleanup").toScala
    val getNewDocJobs = config.getDuration("semrepo.scheduled.get-new-doc-jobs").toScala
