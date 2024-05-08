package eu.assistiot.semantic_repo.core

import akka.actor.typed.ActorSystem

import scala.concurrent.ExecutionContext

/**
 * Trait providing abstract implicits for the ActorSystem and ExecutionContext.
 * These values should be implemented by, e.g., the Main object.
 */
trait ActorSystemUser {
  implicit val system: ActorSystem[Guardian.Command]
  implicit val executionContext: ExecutionContext
}
