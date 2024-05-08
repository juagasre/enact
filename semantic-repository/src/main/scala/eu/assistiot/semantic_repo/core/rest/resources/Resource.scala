package eu.assistiot.semantic_repo.core.rest.resources

import akka.http.scaladsl.server.Route
import com.typesafe.scalalogging.LazyLogging
import eu.assistiot.semantic_repo.core.datamodel.search.SearchSupport
import eu.assistiot.semantic_repo.core.rest.json.JsonSupport

trait Resource extends JsonSupport, SearchSupport, LazyLogging:
  /**
   * The main route of this resource. To implement in subclasses.
   */
  def route: Route
