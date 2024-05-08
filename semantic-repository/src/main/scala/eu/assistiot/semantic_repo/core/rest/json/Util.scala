package eu.assistiot.semantic_repo.core.rest.json

import akka.http.scaladsl.model.DateTime

object Util:
  /**
   * Converts a Unix MILLISECOND timestamp to a readable ISO date time string.
   * @param ts timestamp in milliseconds
   * @return ISO date time string
   */
  def timestampToIso(ts: Long) = DateTime(ts).toIsoDateTimeString()
