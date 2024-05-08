package eu.assistiot.semantic_repo.core.storage

/**
 * Enumeration of S3-compatible buckets semrepo will use.
 */
enum Bucket(val name: String):
  case DataModel extends Bucket("semrepo-datamodel")
  case DocSource extends Bucket("semrepo-doc-source")
  case DocOutput extends Bucket("semrepo-doc-output")
