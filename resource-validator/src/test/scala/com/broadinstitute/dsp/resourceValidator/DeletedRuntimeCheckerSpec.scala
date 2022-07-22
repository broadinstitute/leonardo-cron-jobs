package com.broadinstitute.dsp
package resourceValidator

import cats.effect.IO
import cats.mtl.Ask
import com.broadinstitute.dsp.Generators._
import com.broadinstitute.dsp.resourceValidator.InitDependenciesHelper.initRuntimeCheckerDeps
import com.google.cloud.compute.v1.{Instance, Operation}
import com.google.cloud.dataproc.v1.{Cluster, ClusterOperationMetadata}
import fs2.Stream
import org.broadinstitute.dsde.workbench.google2.mock.{
  BaseFakeGoogleDataprocService,
  FakeGoogleBillingInterpreter,
  FakeGoogleComputeService
}
import org.broadinstitute.dsde.workbench.google2.{DataprocClusterName, RegionName, ZoneName}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import cats.effect.unsafe.implicits.global
import com.google.api.gax.longrunning.OperationFuture
import com.google.protobuf.Empty
import org.broadinstitute.dsde.workbench.util2.InstanceName
import org.scalatest.flatspec.AnyFlatSpec

class DeletedRuntimeCheckerSpec extends AnyFlatSpec with CronJobsTestSuite {
  it should "return None if runtime no longer exists in Google" in {
    val computeService = new FakeGoogleComputeService {
      override def getInstance(project: GoogleProject, zone: ZoneName, instanceName: InstanceName)(implicit
        ev: Ask[IO, TraceId]
      ): IO[Option[Instance]] = IO.pure(None)
    }
    val dataprocService = new BaseFakeGoogleDataprocService {
      override def getCluster(project: GoogleProject, region: RegionName, clusterName: DataprocClusterName)(implicit
        ev: Ask[IO, TraceId]
      ): IO[Option[Cluster]] = IO.pure(None)
    }

    val runtimeCheckerDeps =
      initRuntimeCheckerDeps(googleComputeService = computeService, googleDataprocService = dataprocService)

    forAll { (runtime: Runtime, dryRun: Boolean) =>
      val dbReader = new FakeDbReader {
        override def getDeletedRuntimes: fs2.Stream[IO, Runtime] = Stream.emit(runtime)
      }
      val deletedRuntimeChecker = DeletedRuntimeChecker.impl(dbReader, runtimeCheckerDeps)
      val res = deletedRuntimeChecker.checkResource(runtime, dryRun)
      res.unsafeRunSync() shouldBe None
    }
  }

  it should "return Runtime if runtime still exists in Google" in {
    forAll { (runtime: Runtime, dryRun: Boolean) =>
      val dbReader = new FakeDbReader {
        override def getDeletedRuntimes: fs2.Stream[IO, Runtime] = Stream.emit(runtime)
      }
      val computeService = new FakeGoogleComputeService {
        override def getInstance(project: GoogleProject, zone: ZoneName, instanceName: InstanceName)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[Instance]] = {
          val instance = Instance.newBuilder().build()
          IO.pure(Some(instance))
        }

        override def deleteInstance(project: GoogleProject, zone: ZoneName, instanceName: InstanceName)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[OperationFuture[Operation, Operation]]] =
          if (dryRun) IO.raiseError(fail("this shouldn't be called")) else IO.pure(None)
      }
      val dataprocService = new BaseFakeGoogleDataprocService {
        override def getCluster(project: GoogleProject, region: RegionName, clusterName: DataprocClusterName)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[Cluster]] = {
          val cluster = Cluster.newBuilder().build()
          IO.pure(Some(cluster))
        }

        override def deleteCluster(project: GoogleProject, region: RegionName, clusterName: DataprocClusterName)(
          implicit ev: Ask[IO, TraceId]
        ): IO[Option[OperationFuture[Empty, ClusterOperationMetadata]]] =
          if (dryRun) IO.raiseError(fail("this shouldn't be called")) else IO.pure(None)
      }

      val runtimeCheckerDeps =
        initRuntimeCheckerDeps(googleComputeService = computeService, googleDataprocService = dataprocService)

      val deletedRuntimeChecker = DeletedRuntimeChecker.impl(dbReader, runtimeCheckerDeps)
      val res = deletedRuntimeChecker.checkResource(runtime, dryRun)
      res.unsafeRunSync() shouldBe Some(runtime)
    }
  }

  it should "return None if a dataproc cluster exists in google but has billing disabled and it is not a dry run" in {
    forAll { (runtime: Runtime) =>
      val dbReader = new FakeDbReader {
        override def getDeletedRuntimes: fs2.Stream[IO, Runtime] = Stream.emit(runtime)
      }

      val dataprocService = new BaseFakeGoogleDataprocService {
        override def getCluster(project: GoogleProject, region: RegionName, clusterName: DataprocClusterName)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[Cluster]] = {
          val cluster = Cluster.newBuilder().build()
          IO.pure(Some(cluster))
        }

        override def deleteCluster(project: GoogleProject, region: RegionName, clusterName: DataprocClusterName)(
          implicit ev: Ask[IO, TraceId]
        ): IO[Option[OperationFuture[Empty, ClusterOperationMetadata]]] =
          IO.pure(None)
      }

      val billingService = new FakeGoogleBillingInterpreter {
        override def isBillingEnabled(project: GoogleProject)(implicit ev: Ask[IO, TraceId]): IO[Boolean] =
          IO.pure(false)
      }
      val runtimeCheckerDeps =
        initRuntimeCheckerDeps(googleDataprocService = dataprocService, googleBillingService = billingService)

      val deletedRuntimeChecker = DeletedRuntimeChecker.impl(dbReader, runtimeCheckerDeps)
      val res = deletedRuntimeChecker.checkResource(runtime, false)
      res.unsafeRunSync() shouldBe None
    }
  }
}
