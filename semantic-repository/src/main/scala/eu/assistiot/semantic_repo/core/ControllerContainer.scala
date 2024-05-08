package eu.assistiot.semantic_repo.core

import akka.actor.typed.ActorSystem
import eu.assistiot.semantic_repo.core.controller.*
import fr.davit.akka.http.metrics.prometheus.{PrometheusRegistry, PrometheusSettings}
import io.prometheus.client.CollectorRegistry

object ControllerContainer:
  def apply(sys: ActorSystem[Guardian.Command]): ControllerContainer =
    ControllerContainer(
      webhook = new WebhookController(sys),
      metrics = new MetricsController(),
    )

/**
 * Container for all controllers in SemRepo.
 */
final case class ControllerContainer(
  webhook: WebhookController,
  metrics: MetricsController,
)
