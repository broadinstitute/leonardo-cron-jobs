package com.broadinstitute.dsp
package janitor

import cats.effect.IO
import cats.mtl.Ask
import com.broadinstitute.dsp.Generators._
import fs2.Stream
import io.circe.Encoder
import org.broadinstitute.dsde.workbench.google2.mock.{
  FakeGoogleBillingInterpreter,
  FakeGooglePublisher,
  FakeGoogleStorageInterpreter
}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.openTelemetry.FakeOpenTelemetryMetricsInterpreter
import org.broadinstitute.dsde.workbench.util2.messaging.CloudPublisher
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
        override def publishOne[MessageType](message: MessageType, messageAttributes: Map[String, String])(implicit
          evidence: Encoder[MessageType],
          ev: Ask[IO, TraceId]
        ): IO[Unit] =
          if (dryRun)
            IO.raiseError(fail("Shouldn't publish message in dryRun mode"))
          else {
            count = count + 1
            super.publishOne(message, Map("leonardo" -> "true"))(evidence, ev)
          }
      }

      val deps = initDeps(publisher)
      val checker = NodepoolRemover.impl(dbReader, deps)
      val res = checker.checkResource(n, dryRun)
      n.cloudContext.cloudProvider match {
        case CloudProvider.Gcp =>
          res.unsafeRunSync() shouldBe Some(n)
          count shouldBe (if (dryRun) 0 else 1)
        case CloudProvider.Azure =>
          res.unsafeRunSync() shouldBe None
          count shouldBe 0
      }
    }
  }

  it should "not send DeleteNodepoolMessage when nodepool's kubernetes cluster is also detected to be deleted" in {
    forAll { (nodepoolWithAssociatedCluster: Nodepool, clusterToRemove: KubernetesClusterToRemove) =>
      val clusterToRemoveCandidate = clusterToRemove.copy(id = nodepoolWithAssociatedCluster.kubernetesClusterId,
                                                          nodepoolWithAssociatedCluster.cloudContext
      )
      // nodepool that's not on a kubernetes clusters candidates to remove
      val nodepoolToRemove = nodepoolWithAssociatedCluster.copy(
        nodepoolId = nodepoolWithAssociatedCluster.nodepoolId + 1,
        kubernetesClusterId = clusterToRemoveCandidate.id + 1,
        cloudContext = clusterToRemoveCandidate.cloudContext
      )
      val dbReader = new FakeDbReader {
        override def getNodepoolsToDelete: Stream[IO, Nodepool] =
          Stream.emits(List(nodepoolWithAssociatedCluster, nodepoolToRemove))

        override def getKubernetesClustersToDelete: Stream[IO, KubernetesClusterToRemove] =
          Stream.emit(
            clusterToRemoveCandidate
          )
      }

      var count = 0

      val publisher = new FakeGooglePublisher {
        override def publishOne[MessageType](message: MessageType, messageAttributes: Map[String, String])(implicit
          evidence: Encoder[MessageType],
          ev: Ask[IO, TraceId]
        ): IO[Unit] = {
          count = count + 1
          message shouldBe nodepoolToRemove
          super.publishOne(message, Map("leonardo" -> "true"))(evidence, ev)
        }
      }

      val deps = initDeps(publisher)
      val checker = NodepoolRemover.impl(dbReader, deps)
      val res = checker.run(false)
      res.unsafeRunSync()
      count shouldBe 1
    }
  }

  private def initDeps(publisher: CloudPublisher[IO]): LeoPublisherDeps[IO] = {
    val checkRunnerDeps =
      CheckRunnerDeps(ConfigSpec.config.reportDestinationBucket,
                      FakeGoogleStorageInterpreter,
                      FakeOpenTelemetryMetricsInterpreter
      )
    new LeoPublisherDeps[IO](publisher, checkRunnerDeps, FakeGoogleBillingInterpreter)
  }
}
