package com.broadinstitute.dsp
package resourceValidator

import cats.effect.IO
import cats.mtl.Ask
import com.broadinstitute.dsp.Generators._
import com.broadinstitute.dsp.resourceValidator.InitDependenciesHelper.initKubernetesClusterCheckerDeps
import com.google.container.v1.{Cluster, Operation}
import fs2.Stream
import org.broadinstitute.dsde.workbench.google2.GKEModels.KubernetesClusterId
import org.broadinstitute.dsde.workbench.google2.mock.MockGKEService
import org.broadinstitute.dsde.workbench.model.TraceId
import org.scalatest.flatspec.AnyFlatSpec
import cats.effect.unsafe.implicits.global

class DeletedOrErroredKubernetesClusterCheckerSpec extends AnyFlatSpec with CronJobsTestSuite {
  it should "return None if kubernetes cluster no longer exists in Google" in {
    val gkeService = new MockGKEService {
      override def getCluster(clusterId: KubernetesClusterId)(implicit
        ev: Ask[IO, TraceId]
      ): IO[Option[Cluster]] = IO.pure(None)
    }

    val deps = initKubernetesClusterCheckerDeps(gkeService = gkeService)

    forAll { (cluster: KubernetesCluster, dryRun: Boolean) =>
      val dbReader = new FakeDbReader {
        override def getDeletedAndErroredKubernetesClusters: fs2.Stream[IO, KubernetesCluster] = Stream.emit(cluster)
      }
      val deletedOrErroredKubernetesClusterChecker =
        DeletedOrErroredKubernetesClusterChecker.impl(dbReader, deps)
      val res = deletedOrErroredKubernetesClusterChecker.checkResource(cluster, dryRun)
      res.unsafeRunSync() shouldBe None
    }
  }

  it should "return KubernetesCluster if cluster still exists in Google" in {
    forAll { (cluster: KubernetesCluster, dryRun: Boolean) =>
      val dbReader = new FakeDbReader {
        override def getDeletedAndErroredKubernetesClusters: fs2.Stream[IO, KubernetesCluster] = Stream.emit(cluster)
      }
      val gkeService = new MockGKEService {
        override def getCluster(clusterId: KubernetesClusterId)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[Cluster]] = {
          val cluster = Cluster.newBuilder().build()
          IO.pure(Some(cluster))
        }

        override def deleteCluster(clusterId: KubernetesClusterId)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[Operation]] =
          if (dryRun) IO.raiseError(fail("this shouldn't be called")) else IO.pure(Some(Operation.newBuilder().build()))
      }

      val deps = initKubernetesClusterCheckerDeps(gkeService = gkeService)

      val deletedOrErroredKubernetesClusterChecker = DeletedOrErroredKubernetesClusterChecker.impl(dbReader, deps)
      val res = deletedOrErroredKubernetesClusterChecker.checkResource(cluster, dryRun)
      res.unsafeRunSync() shouldBe Some(cluster)
    }
  }
}
