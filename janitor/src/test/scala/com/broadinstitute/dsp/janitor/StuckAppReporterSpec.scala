package com.broadinstitute.dsp
package janitor

import cats.effect.IO
import fs2.Stream
import org.broadinstitute.dsde.workbench.google2.GoogleBillingService
import org.broadinstitute.dsde.workbench.google2.mock.{
  FakeGoogleBillingInterpreter,
  FakeGooglePublisher,
  FakeGoogleStorageInterpreter
}
import org.broadinstitute.dsde.workbench.openTelemetry.FakeOpenTelemetryMetricsInterpreter
import org.broadinstitute.dsde.workbench.util2.messaging.CloudPublisher
import org.scalatest.flatspec.AnyFlatSpec
import cats.effect.unsafe.implicits.global

import java.sql.Timestamp
import java.time.LocalDateTime

final class StuckAppReporterSpec extends AnyFlatSpec with CronJobsTestSuite {
  it should "Only log apps detected to be stuck in deleting or creating status when dryRun is False" in {
    forAll { (dryRun: Boolean) =>
      val stuckApp = AppToReport(
        10L,
        "test_app_name",
        "DELETING",
        Timestamp.valueOf(LocalDateTime.now())
      )
      val dbReader = new FakeDbReader {
        override def getStuckAppToReport: Stream[IO, AppToReport] =
          Stream.emit(stuckApp)
      }

      val publisher = new FakeGooglePublisher {}
      val deps = initDeps(publisher)
      val checker = StuckAppReporter.impl(dbReader, deps)
      val res = checker.checkResource(stuckApp, dryRun)

      if (dryRun) res.unsafeRunSync() shouldBe None
      else res.unsafeRunSync() shouldBe Some(stuckApp)
    }
  }

  private def initDeps(publisher: CloudPublisher[IO],
                       billingService: GoogleBillingService[IO] = FakeGoogleBillingInterpreter
  ): LeoPublisherDeps[IO] = {
    val checkRunnerDeps =
      CheckRunnerDeps(ConfigSpec.config.reportDestinationBucket,
                      FakeGoogleStorageInterpreter,
                      FakeOpenTelemetryMetricsInterpreter
      )
    new LeoPublisherDeps[IO](publisher, checkRunnerDeps, billingService)
  }
}
