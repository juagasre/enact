package eu.assistiot.semantic_repo.core.rest.json.update

import akka.http.scaladsl.model.Uri
import eu.assistiot.semantic_repo.core.AppConfig
import eu.assistiot.semantic_repo.core.datamodel.MongoModel
import eu.assistiot.semantic_repo.core.rest.ObjectPathContext
import eu.assistiot.semantic_repo.core.rest.json.WebhookContextClientModel
import eu.assistiot.semantic_repo.core.rest.resources.{ModelResource, ModelVersionResource, NamespaceResource}
import org.bson.conversions.Bson
import org.mongodb.scala.model.Updates as u

case class WebhookUpdateModel(action: String, context: Option[WebhookContextClientModel], callback: String)
  extends UpdateModel[MongoModel.Webhook]:

  protected def validateInner(implicit updateMode: UpdateMode): Unit =
    if updateMode != UpdateMode.Insert then
      throw new RuntimeException("Updating existing webhooks is not allowed.")
    if !MongoModel.Webhook.keyToAction.contains(action) then
      throw new RuntimeException("Unsupported action.")

    if callback.length > AppConfig.Limits.Webhook.maxCallbackLength then
      throw new RuntimeException("Callback URI is too long.")
    val uri = Uri(callback, Uri.ParsingMode.Strict)
    if !uri.isAbsolute then
      throw new RuntimeException("Callback URI must be absolute.")
    if uri.scheme != "http" && uri.scheme != "https" then
      throw new RuntimeException("Invalid callback scheme.")

    context match
      case Some(WebhookContextClientModel(namespace, model, version)) =>
        version match
          case Some(ver) =>
            if !ModelVersionResource.verRegex.matches(ver) then
              throw new RuntimeException(s"Version tags must match regex: ${ModelVersionResource.verRegexString}")
            if model.isEmpty || namespace.isEmpty then
              throw new RuntimeException("Context with a version must also have a model and namespace specified.")
          case None =>

        model match
          case Some(m) =>
            if !ModelResource.modRegex.matches(m) then
              throw new RuntimeException(s"Model names must match regex: ${ModelResource.modRegexString}")
            if namespace.isEmpty then
              throw new RuntimeException("Context with a model must also have a namespace specified.")
          case None =>

        namespace match
          case Some(ns) =>
            if !NamespaceResource.nsRegex.matches(ns) then
              throw new RuntimeException(s"Namespace names must match regex: ${NamespaceResource.nsRegexString}")
          case None =>
      case _ =>

  protected def validatePreUpdateInner(entity: MongoModel.Webhook): Unit = ()

  protected def getMongoUpdates: Seq[Option[Bson]] = Seq()
