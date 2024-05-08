package eu.assistiot.semantic_repo.core.rest.resources

import akka.http.scaladsl.model.*
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.*
import eu.assistiot.semantic_repo.core.controller.MetricsController
import fr.davit.akka.http.metrics.core.scaladsl.server.HttpMetricsDirectives.*
import fr.davit.akka.http.metrics.prometheus.marshalling.PrometheusMarshallers.*

/**
 * Exposes metrics in the Prometheus format.
 * @param metricsController metrics controller
 */
class PrometheusResource(metricsController: MetricsController) extends Resource:
  val route = pathLabeled("metrics") {
    get {
      metrics(metricsController.metricsRegistry)
    }
  }
