package com.broadinstitute.dsp
package zombieMonitor

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.mtl.Ask
import com.azure.resourcemanager.containerservice.models
import com.broadinstitute.dsp.Generators._
import fs2.Stream
import org.broadinstitute.dsde.workbench.azure.mock.FakeAzureContainerService
import org.broadinstitute.dsde.workbench.azure.{AzureCloudContext, AzureContainerService}
import org.broadinstitute.dsde.workbench.google2.GKEModels.NodepoolId
import org.broadinstitute.dsde.workbench.google2.GKEService
import org.broadinstitute.dsde.workbench.google2.mock.{FakeGoogleStorageInterpreter, MockGKEService}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.openTelemetry.FakeOpenTelemetryMetricsInterpreter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.mockito.MockitoSugar.mock

class DeletedOrErroredNodepoolCheckerSpec extends AnyFlatSpec with CronJobsTestSuite {
  it should "report nodepool if it doesn't exist in the cloud but still active in leonardo DB" in {
    forAll { (nodepoolToScan: Nodepool, dryRun: Boolean) =>
      val dbReader = new FakeDbReader {
        override def getk8sNodepoolsToDeleteCandidate: Stream[IO, Nodepool] =
          Stream.emit(nodepoolToScan)
        override def updateNodepoolAndAppStatus(id: Long, status: String): IO[Unit] =
          if (dryRun) IO.raiseError(fail("this shouldn't be called in dryRun mode")) else IO.unit
      }
      val gkeService = new MockGKEService {
        override def getNodepool(nodepoolId: NodepoolId)(implicit
          ev: cats.mtl.Ask[IO, TraceId]
        ): IO[Option[com.google.container.v1.NodePool]] = IO.pure(None)
      }
      val aksService = new FakeAzureContainerService {
        override def listClusters(cloudContext: AzureCloudContext)(implicit
          ev: Ask[IO, TraceId]
        ): IO[List[models.KubernetesCluster]] =
          IO.pure(List.empty)
      }
      val deps = initDeps(gkeService, aksService)
      val checker = DeletedOrErroredNodepoolChecker.impl(dbReader, deps)
      val res = checker.checkResource(nodepoolToScan, dryRun)
      res.unsafeRunSync() shouldBe Some(nodepoolToScan)
    }
  }

  it should "not report nodepool if it still exists in the cloud and active in leonardo DB" in {
    forAll { (nodepoolToScan: Nodepool, dryRun: Boolean) =>
      val dbReader = new FakeDbReader {
        override def getk8sNodepoolsToDeleteCandidate: Stream[IO, Nodepool] =
          Stream.emit(nodepoolToScan)
      }
      val gkeService = new MockGKEService {
        override def getNodepool(nodepoolId: NodepoolId)(implicit
          ev: cats.mtl.Ask[IO, TraceId]
        ): IO[Option[com.google.container.v1.NodePool]] =
          IO.pure(Some(com.google.container.v1.NodePool.newBuilder().build()))
      }
      val aksService = new FakeAzureContainerService {
        override def listClusters(cloudContext: AzureCloudContext)(implicit
          ev: Ask[IO, TraceId]
        ): IO[List[models.KubernetesCluster]] =
          IO.pure(List(mock[models.KubernetesCluster]))
      }
      val deps = initDeps(gkeService, aksService)
      val checker = DeletedOrErroredNodepoolChecker.impl(dbReader, deps)
      val res = checker.checkResource(nodepoolToScan, dryRun)
      res.unsafeRunSync() shouldBe None
    }
  }

  it should "report nodepool if it still exist in google in ERROR and active in leonardo DB" in {
    forAll { (nodepoolToScan: Nodepool, dryRun: Boolean) =>
      val dbReader = new FakeDbReader {
        override def getk8sNodepoolsToDeleteCandidate: Stream[IO, Nodepool] =
          Stream.emit(nodepoolToScan)

        override def updateNodepoolAndAppStatus(id: Long, status: String): IO[Unit] =
          if (dryRun) IO.raiseError(fail("this shouldn't be called in dryRun mode")) else IO.unit
      }
      val gkeService = new MockGKEService {
        override def getNodepool(nodepoolId: NodepoolId)(implicit
          ev: cats.mtl.Ask[IO, TraceId]
        ): IO[Option[com.google.container.v1.NodePool]] =
          IO.pure(
            Some(
              com.google.container.v1.NodePool
                .newBuilder()
                .setStatus(com.google.container.v1.NodePool.Status.ERROR)
                .build()
            )
          )
      }
      val aksService = new FakeAzureContainerService {
        override def listClusters(cloudContext: AzureCloudContext)(implicit
          ev: Ask[IO, TraceId]
        ): IO[List[models.KubernetesCluster]] =
          IO.pure(List.empty)
      }
      val deps = initDeps(gkeService, aksService)
      val checker = DeletedOrErroredNodepoolChecker.impl(dbReader, deps)
      val res = checker.checkResource(nodepoolToScan, dryRun)
      res.unsafeRunSync() shouldBe Some(nodepoolToScan)
    }
  }

  def initDeps(gkeService: GKEService[IO], aksService: AzureContainerService[IO]): KubernetesClusterCheckerDeps[IO] = {
    val config = Config.appConfig.toOption.get
    val checkRunnerDeps =
      CheckRunnerDeps(config.reportDestinationBucket, FakeGoogleStorageInterpreter, FakeOpenTelemetryMetricsInterpreter)
    new KubernetesClusterCheckerDeps[IO](checkRunnerDeps, gkeService, aksService)
  }
}
