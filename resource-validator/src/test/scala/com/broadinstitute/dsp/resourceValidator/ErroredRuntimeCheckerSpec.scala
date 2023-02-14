package com.broadinstitute.dsp
package resourceValidator

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.mtl.Ask
import com.azure.resourcemanager.compute.models.VirtualMachine
import com.azure.resourcemanager.resources.fluentcore.model.Accepted
import com.broadinstitute.dsp.Generators._
import com.broadinstitute.dsp.resourceValidator.InitDependenciesHelper._
import com.google.api.gax.longrunning.OperationFuture
import com.google.cloud.compute.v1.{Instance, Operation}
import com.google.cloud.dataproc.v1.ClusterStatus.State
import com.google.cloud.dataproc.v1.{Cluster, ClusterOperationMetadata, ClusterStatus}
import com.google.protobuf.Empty
import fs2.Stream
import org.broadinstitute.dsde.workbench.azure.AzureCloudContext
import org.broadinstitute.dsde.workbench.azure.mock.FakeAzureVmService
import org.broadinstitute.dsde.workbench.google2.mock.{BaseFakeGoogleDataprocService, FakeGoogleComputeService}
import org.broadinstitute.dsde.workbench.google2.{DataprocClusterName, RegionName, ZoneName}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.util2.InstanceName
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.mockito.MockitoSugar.mock

class ErroredRuntimeCheckerSpec extends AnyFlatSpec with CronJobsTestSuite {
  it should "return None if runtime no longer exists in the cloud" in {
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
        override def getErroredRuntimes: fs2.Stream[IO, Runtime] = Stream.emit(runtime)
      }
      val erroredRuntimeChecker = ErroredRuntimeChecker.iml(dbReader, runtimeCheckerDeps)
      val res = erroredRuntimeChecker.checkResource(runtime, dryRun)
      res.unsafeRunSync() shouldBe None
    }
  }

  it should "return Runtime if runtime still exists in the cloud" in {
    forAll { (runtime: Runtime, dryRun: Boolean) =>
      val dbReader = new FakeDbReader {
        override def getErroredRuntimes: fs2.Stream[IO, Runtime] = Stream.emit(runtime)
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

      val azureVmService = new FakeAzureVmService {
        override def getAzureVm(name: InstanceName, cloudContext: AzureCloudContext)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[VirtualMachine]] =
          IO.pure(Some(mock[VirtualMachine]))
        override def deleteAzureVm(name: InstanceName, cloudContext: AzureCloudContext, forceDeletion: Boolean)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[Accepted[Void]]] =
          if (dryRun) IO.raiseError(fail("this shouldn't be called")) else IO.pure(None)
      }

      val runtimeCheckerDeps =
        initRuntimeCheckerDeps(googleComputeService = computeService,
                               googleDataprocService = dataprocService,
                               azureVmService = azureVmService
        )

      val erroredRuntimeChecker = ErroredRuntimeChecker.iml(dbReader, runtimeCheckerDeps)
      val res = erroredRuntimeChecker.checkResource(runtime, dryRun)
      res.unsafeRunSync() shouldBe Some(runtime)
    }
  }

  it should "return None if dataproc cluster still exists in Google in Error status and it is not a dry run" in {
    implicit val arbA = arbDataprocRuntime
    forAll { (runtime: Runtime.Dataproc) =>
      val dbReader = new FakeDbReader {
        override def getErroredRuntimes: fs2.Stream[IO, Runtime] = Stream.emit(runtime)
      }
      val dataprocService = new BaseFakeGoogleDataprocService {
        override def getCluster(project: GoogleProject, region: RegionName, clusterName: DataprocClusterName)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[Cluster]] = {
          val cluster = Cluster.newBuilder().setStatus(ClusterStatus.newBuilder().setState(State.ERROR)).build()
          IO.pure(Some(cluster))
        }

        override def deleteCluster(project: GoogleProject, region: RegionName, clusterName: DataprocClusterName)(
          implicit ev: Ask[IO, TraceId]
        ): IO[Option[OperationFuture[Empty, ClusterOperationMetadata]]] =
          IO.raiseError(fail("this shouldn't be called"))
      }

      val rt = runtime.copy(cloudService = CloudService.Dataproc)
      val runtimeCheckerDeps =
        initRuntimeCheckerDeps(googleDataprocService = dataprocService)

      val erroredRuntimeChecker = ErroredRuntimeChecker.iml(dbReader, runtimeCheckerDeps)

      val res = erroredRuntimeChecker.checkResource(rt, false)
      res.unsafeRunSync() shouldBe None
    }
  }
}
