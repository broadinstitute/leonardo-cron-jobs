package com.broadinstitute.dsp
package zombieMonitor

import org.broadinstitute.dsde.workbench.azure.{AzureAppRegistrationConfig, ClientId, ClientSecret, ManagedAppTenantId}

import java.nio.file.Paths
import org.broadinstitute.dsde.workbench.model.google.GcsBucketName
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConfigSpec extends AnyFlatSpec with Matchers {
  it should "read config file correctly" in {
    val config = Config.appConfig
    val expectedPathToCredential = Paths.get("path-to-credential")
    val expectedReportDestinationBucket = GcsBucketName("qi-test")
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
        expectedReportDestinationBucket,
        AzureAppRegistrationConfig(ClientId(""), ClientSecret(""), ManagedAppTenantId(""))
      ),
      Prometheus(9098)
    )

    config shouldBe Right(expectedConfig)
  }
}

object ConfigSpec {
  def config = Config.appConfig.toOption.get
}
