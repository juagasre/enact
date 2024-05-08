package eu.assistiot.semantic_repo.core.rest.resources

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import eu.assistiot.semantic_repo.core.buildinfo.BuildInfo
import eu.assistiot.semantic_repo.core.datamodel.InfoResponse
import fr.davit.akka.http.metrics.core.scaladsl.server.HttpMetricsDirectives.*

object InfoResource extends Resource:
  val route: Route = pathLabeled("info") {
    getInfoRoute
  } ~ pathLabeled("version") {
    getVersionRoute
  } ~ pathLabeled("health") {
    getHealthRoute
  }

  def getInfoRoute = pathEndOrSingleSlash {
    get {
      complete(StatusCodes.OK, InfoResponse(
        BuildInfo.name,
        BuildInfo.version
      ))
    }
  }

  def getVersionRoute = pathEndOrSingleSlash {
    get {
      complete(StatusCodes.OK, BuildInfo.version)
    }
  }

  def getHealthRoute = pathEndOrSingleSlash {
    get {
      complete(StatusCodes.OK)
    }
  }
