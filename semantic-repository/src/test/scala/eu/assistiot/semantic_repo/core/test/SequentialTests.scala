package eu.assistiot.semantic_repo.core.test

import eu.assistiot.semantic_repo.core.test.datamodel.*
import eu.assistiot.semantic_repo.core.test.rest.*
import eu.assistiot.semantic_repo.core.test.storage.*
import org.scalatest.Stepwise

/**
 * Ordering of tests that modify the database or minIO.
 * This is to ensure we first clean it up and initialize the DB and buckets properly.
 */
class SequentialTests extends Stepwise(
  new StorageUtilsSpec,
  new MongoModelSpec,
  new NamespaceSpec,
  new ModelSpec,
  new ModelVersionSpec,
  new MetadataSpec,
  new ContentSpec,
  new DocSandboxSpec,
  new DocModelSpec,
  new CascadeDeleteSpec,
  new PagingSpec,
  new WebhookSpec,
)
