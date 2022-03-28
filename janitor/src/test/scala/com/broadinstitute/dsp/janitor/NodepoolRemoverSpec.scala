package com.broadinstitute.dsp
package janitor

import cats.effect.IO
import cats.mtl.Ask
import com.broadinstitute.dsp.Generators._
import fs2.Stream
import io.circe.Encoder
import org.broadinstitute.dsde.workbench.google2.GooglePublisher
import org.broadinstitute.dsde.workbench.google2.mock.{
  FakeGoogleBillingInterpreter,
  FakeGooglePublisher,
  FakeGoogleStorageInterpreter
}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.openTelemetry.FakeOpenTelemetryMetricsInterpreter
import org.scalatest.flatspec.AnyFlatSpec
import cats.effect.unsafe.implicits.global

final class NodepoolRemoverSpec extends AnyFlatSpec with CronJobsTestSuite {
  it should "send DeleteNodepoolMessage when nodepools are detected to be auto-deleted" in {
    forAll { (n: Nodepool, dryRun: Boolean) =>
      val dbReader = new FakeDbReader {
        override def getNodepoolsToDelete: Stream[IO, Nodepool] =
          Stream.emit(n)
      }

      var count = 0

      val publisher = new FakeGooglePublisher {
        override def publishOne[MessageType](message: MessageType)(implicit
          evidence$2: Encoder[MessageType],
          ev: Ask[IO, TraceId]
        ): IO[Unit] =
          if (dryRun)
            IO.raiseError(fail("Shouldn't publish message in dryRun mode"))
          else {
            count = count + 1
            super.publishOne(message)(evidence$2, ev)
          }
      }

      val deps = initDeps(publisher)
      val checker = NodepoolRemover.impl(dbReader, deps)
      val res = checker.checkResource(n, dryRun)

      res.unsafeRunSync() shouldBe Some(n)
      if (dryRun) count shouldBe 0
      else count shouldBe 1
    }
  }

  private def initDeps(publisher: GooglePublisher[IO]): LeoPublisherDeps[IO] = {
    val checkRunnerDeps =
      CheckRunnerDeps(ConfigSpec.config.reportDestinationBucket,
                      FakeGoogleStorageInterpreter,
                      FakeOpenTelemetryMetricsInterpreter
      )
    new LeoPublisherDeps[IO](publisher, checkRunnerDeps, FakeGoogleBillingInterpreter)
  }
}
