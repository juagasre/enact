package eu.assistiot.semantic_repo.core.controller

import fr.davit.akka.http.metrics.prometheus.{Buckets, PrometheusRegistry, PrometheusSettings}
import io.prometheus.client.CollectorRegistry

/**
 * Controller for metrics reported via Prometheus.
 *
 * Used by the main application object to register the HTTP server with the collector.
 * Also used in [[eu.assistiot.semantic_repo.core.rest.resources.PrometheusResource]]
 */
class MetricsController:
  private val bytesBuckets = Buckets(
    0, 16, 64, 256, 1024,
    4 * 1024, 16 * 1024, 64 * 1024, 256 * 1024, 1024 * 1024,
    4 * 1024 * 1024, 16 * 1024 * 1024, 64 * 1024 * 1024, 256 * 1024 * 1024, 1024 * 1024 * 1024,
  )

  private val metricsSettings = PrometheusSettings.default
    .withDurationConfig(Buckets(
      0.001, 0.0025, 0.005, 0.0075, 0.01,
      0.025, 0.05, 0.1, 0.25,
      0.5, 0.75, 1.0, 2.5, 5.0, 10.0, 20.0, 60.0
    ))
    .withSentBytesConfig(bytesBuckets)
    .withReceivedBytesConfig(bytesBuckets)
    .withIncludePathDimension(true)
    .withIncludeMethodDimension(true)

  val prometheusRegistry = CollectorRegistry()
  val metricsRegistry = PrometheusRegistry(prometheusRegistry, metricsSettings)
