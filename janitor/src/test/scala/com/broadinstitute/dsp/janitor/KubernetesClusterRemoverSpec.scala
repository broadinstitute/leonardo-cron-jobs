package com.broadinstitute.dsp
package janitor

import cats.effect.IO
import cats.mtl.Ask
import com.broadinstitute.dsp.Generators._
import fs2.Stream
import io.circe.Encoder
import org.broadinstitute.dsde.workbench.google2.{GoogleBillingService, GooglePublisher}
import org.broadinstitute.dsde.workbench.google2.mock.{
  FakeGoogleBillingInterpreter,
  FakeGooglePublisher,
  FakeGoogleStorageInterpreter
}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.openTelemetry.FakeOpenTelemetryMetricsInterpreter
import org.scalatest.flatspec.AnyFlatSpec
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
final class KubernetesClusterRemoverSpec extends AnyFlatSpec with CronJobsTestSuite {
  it should "send DeleteKubernetesClusterMessage when clusters are detected to be auto-deleted" in {
    forAll { (clusterToRemove: KubernetesClusterToRemove, dryRun: Boolean) =>
      val dbReader = new FakeDbReader {
        override def getKubernetesClustersToDelete: Stream[IO, KubernetesClusterToRemove] =
          Stream.emit(clusterToRemove)
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
      val checker = KubernetesClusterRemover.impl(dbReader, deps)
      val res = checker.checkResource(clusterToRemove, dryRun)
      clusterToRemove.cloudContext.cloudProvider match {
        case CloudProvider.Gcp =>
          res.unsafeRunSync() shouldBe Some(clusterToRemove)
          count shouldBe (if (dryRun) 0 else 1)
        case CloudProvider.Azure =>
          res.unsafeRunSync() shouldBe None
          count shouldBe 0
      }
    }
  }

  it should "do nothing if billing is not enabled" in {
    forAll { (clusterToRemove: KubernetesClusterToRemove, dryRun: Boolean) =>
      val billingService = new FakeGoogleBillingInterpreter {
        override def isBillingEnabled(project: GoogleProject)(implicit ev: Ask[IO, TraceId]): IO[Boolean] =
          IO.pure(false)
      }
      val dbReader = new FakeDbReader {
        override def getKubernetesClustersToDelete: Stream[IO, KubernetesClusterToRemove] =
          Stream.emit(clusterToRemove)
      }

      val publisher = new FakeGooglePublisher {
        override def publishOne[MessageType](message: MessageType)(implicit
          evidence$2: Encoder[MessageType],
          ev: Ask[IO, TraceId]
        ): IO[Unit] =
          IO.raiseError(fail("Shouldn't publish message"))
      }

      val deps = initDeps(publisher, billingService)
      val checker = KubernetesClusterRemover.impl(dbReader, deps)
      val res = checker.checkResource(clusterToRemove, dryRun)

      res.unsafeRunSync() shouldBe None
    }
  }

  private def initDeps(publisher: GooglePublisher[IO],
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
