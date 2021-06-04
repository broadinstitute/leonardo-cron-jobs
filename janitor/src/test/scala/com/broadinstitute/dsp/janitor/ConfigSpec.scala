package com.broadinstitute.dsp
package janitor

import java.nio.file.Paths

import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GoogleProject}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class ConfigSpec extends AnyFlatSpec with Matchers {
  it should "read config file correctly" in {
    val config = Config.appConfig
    val expectedPathToCredential = Paths.get("path-to-credential")
    val expectedReportDestinationBucket = GcsBucketName("test-bucket")
    val expectedConfig = AppConfig(
      DatabaseConfig(
        "jdbc:mysql://localhost:3311/leotestdb?createDatabaseIfNotExist=true&useSSL=false&rewriteBatchedStatements=true&nullNamePatternMatchesAll=true&generateSimpleParameterMetadata=TRUE",
        "leonardo-test",
        "leonardo-test"
      ),
      expectedPathToCredential,
      expectedReportDestinationBucket,
      RuntimeCheckerConfig(
        expectedPathToCredential,
        expectedReportDestinationBucket
      ),
      PubsubConfig(
        GoogleProject("test-project"),
        "leonardo-pubsub"
      )
    )

    config shouldBe Right(expectedConfig)
  }
}

object ConfigSpec {
  def config = Config.appConfig.toOption.get
}
