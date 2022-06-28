package com.broadinstitute.dsp
package nuker

import java.nio.file.Paths

import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConfigSpec extends AnyFlatSpec with Matchers {
  it should "read config file correctly" in {
    val config = Config.appConfig
    val expectedPathToCredential = Paths.get("path-to-credential")
    val expectedConfig = AppConfig(
      PubsubTopicCleanerConfig(GoogleProject("replace-me")),
      expectedPathToCredential,
      Prometheus(9098)
    )

    config shouldBe Right(expectedConfig)
  }
}

object ConfigSpec {
  def config = Config.appConfig.toOption.get
}
