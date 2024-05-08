package eu.assistiot.semantic_repo.core.rest

import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters as f

object ObjectPathContext:
  /**
   * Construct a [[ModelVersionContext]] from a full path (ns/model/version).
   * This does not check if the path is actually valid.
   * @param path path to the model version
   * @return model version context
   */
  def modelVersionFromPath(path: String) =
    val split = path.split('/')
    if split.length != 3 || split.exists(_.isEmpty) then
      throw new IllegalArgumentException("Path is not a valid model version path")
    ModelVersionContext(split(0), split(1), split(2))

  /**
   * Construct an [[ObjectPathContext]] from namespace, model, version, all of which are optional.
   * This is useful for webhook context handling.
   * @param ns namespace
   * @param model model
   * @param version model version
   * @return object path context
   */
  def fromParts(ns: Option[String], model: Option[String], version: Option[String]): ObjectPathContext =
    ns match
      case None => RootContext
      case Some(nsV) => model match
        case None => NamespaceContext(nsV)
        case Some(modelV) => version match
          case None => ModelContext(nsV, modelV)
          case Some(versionV) => ModelVersionContext(nsV, modelV, versionV)

/**
 * Holder for handy passing of ModelVersion information.
 * Pass it as an implicit to nested routes.
 */
trait ObjectPathContext:
  /**
   * Path to this object.
   * @return
   */
  def path: String
  def getNamespace: Option[String] = None
  def getModel: Option[String] = None
  def getModelVersion: Option[String] = None
  def namespaceFilter: Bson
  def modelFilter: Bson
  def modelVersionFilter: Bson

/**
 * Path context supporting at least namespaces.
 */
trait AbstractNamespaceContext extends ObjectPathContext:
  def ns: String
  final def namespacePath = ns
  override def getNamespace = Some(ns)
  override def namespaceFilter = f.eq("name", ns)
  override def modelFilter = f.eq("namespaceName", ns)
  override def modelVersionFilter = modelFilter

/**
 * Path context supporting at least models.
 */
trait AbstractModelContext extends AbstractNamespaceContext:
  def model: String
  final def modelPath = s"$ns/$model"
  override def getModel = Some(model)
  override def modelFilter =
    f.and(f.eq("namespaceName", ns), f.eq("name", model))
  override def modelVersionFilter =
    f.and(f.eq("namespaceName", ns), f.eq("modelName", model))

/**
 * Path context supporting model versions.
 */
trait AbstractModelVersionContext extends AbstractModelContext:
  def version: String
  final def modelVersionPath = s"$ns/$model/$version"
  override def getModelVersion = Some(version)
  override def modelVersionFilter = f.and(
    f.eq("namespaceName", ns),
    f.eq("modelName", model),
    f.eq("version", version),
  )

case object RootContext extends ObjectPathContext:
  override def namespaceFilter = f.empty()
  override def modelFilter = f.empty()
  override def modelVersionFilter = f.empty()
  override def path = ""

case class NamespaceContext(ns: String) extends AbstractNamespaceContext:
  override def path = namespacePath

case class ModelContext(ns: String, model: String) extends AbstractModelContext:
  override def path = modelPath

case class ModelVersionContext(ns: String, model: String, version: String) extends AbstractModelVersionContext:
  override def path = modelVersionPath

/**
 * Generates a MongoDB filter for finding the relevant namespace.
 * @param opc object path context
 * @return Bson filter
 */
def namespaceFilter(implicit opc: ObjectPathContext): Bson = opc.namespaceFilter

/**
 * Generates a MongoDB filter for finding the relevant model.
 * @param opc object path context
 * @return Bson filter
 */
def modelFilter(implicit opc: ObjectPathContext): Bson = opc.modelFilter

/**
 * Generates a MongoDB filter for finding the relevant model version.
 * @param opc object path context
 * @return Bson filter
 */
def modelVersionFilter(implicit opc: ObjectPathContext): Bson = opc.modelVersionFilter

/**
 * Produces a string describing the path to access the current namespace.
 * @param nc namespace context
 * @return human-readable path
 */
def namespacePath(implicit nc: AbstractNamespaceContext): String = nc.namespacePath

/**
 * Produces a string describing the path to access the current model.
 * @param mc model context
 * @return human-readable path
 */
def modelPath(implicit mc: AbstractModelContext): String = mc.modelPath

/**
 * Produces a string describing the path to access the current model version.
 * @param mvc model version context
 * @return human-readable path
 */
def modelVersionPath(implicit mvc: AbstractModelVersionContext): String = mvc.modelVersionPath
