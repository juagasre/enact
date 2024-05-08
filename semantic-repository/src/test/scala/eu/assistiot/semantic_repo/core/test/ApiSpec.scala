package eu.assistiot.semantic_repo.core.test

import akka.actor.typed.{ActorSystem, Behavior}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.typesafe.config.ConfigFactory
import eu.assistiot.semantic_repo.core.{AppConfig, ControllerContainer, Guardian}
import eu.assistiot.semantic_repo.core.rest.json.JsonSupport
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

abstract class ApiSpec extends AnyWordSpec, Matchers, ScalatestRouteTest, JsonSupport, OptionValues:
  /**
   * Override to replace the guardian actor with something.
   * @return
   */
  protected def getGuardian: Behavior[Guardian.Command] = Guardian()

  lazy final val systemInternal: ActorSystem[Guardian.Command] = ActorSystem(getGuardian, "http", AppConfig.getConfig)

  override protected def createActorSystem() = systemInternal.classicSystem

  val controllers = ControllerContainer(systemInternal)

  // setup config for tests
  AppConfig.setNewConfig(ConfigFactory.parseString("""
    semrepo.limits.docs.max-upload-size = 15K
    semrepo.limits.docs.max-files-in-upload = 10
    semrepo.limits.docs.sandbox-expiry = 300ms
  """).withFallback(ConfigFactory.load()))

