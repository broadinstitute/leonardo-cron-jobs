package com.broadinstitute.dsp
package resourceValidator

import cats.implicits._
import cats.effect.IO
import cats.mtl.Ask
import com.broadinstitute.dsp.resourceValidator.InitDependenciesHelper._
import com.google.cloud.compute.v1.{Instance, Operation}
import com.google.cloud.dataproc.v1.ClusterStatus.State
import com.google.cloud.dataproc.v1.{Cluster, ClusterOperationMetadata, ClusterStatus}
import fs2.Stream
import org.broadinstitute.dsde.workbench.google2.DataprocRole.{Master, SecondaryWorker, Worker}
import org.broadinstitute.dsde.workbench.google2.mock.{
  BaseFakeGoogleDataprocService,
  FakeComputeOperationFuture,
  FakeDataprocClusterOperationFutureOp,
  FakeGoogleComputeService
}
import org.broadinstitute.dsde.workbench.google2.{
  DataprocClusterName,
  DataprocOperation,
  DataprocRoleZonePreemptibility,
  OperationName,
  RegionName,
  ZoneName
}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import com.broadinstitute.dsp.resourceValidator.StoppedRuntimeCheckerSpec._
import org.scalatest.flatspec.AnyFlatSpec
import cats.effect.unsafe.implicits.global
import com.azure.resourcemanager.compute.models.VirtualMachine
import com.broadinstitute.dsp.Generators.genRuntime
import com.google.api.gax.longrunning.OperationFuture
import org.broadinstitute.dsde.workbench.azure.AzureCloudContext
import org.broadinstitute.dsde.workbench.azure.mock.FakeAzureVmService
import org.broadinstitute.dsde.workbench.util2.InstanceName
import org.scalacheck.Arbitrary
import org.scalatestplus.mockito.MockitoSugar.mock

final class StoppedRuntimeCheckerSpec extends AnyFlatSpec with CronJobsTestSuite {
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

    import com.broadinstitute.dsp.Generators.arbRuntime
    forAll { (runtime: Runtime, dryRun: Boolean) =>
      val dbReader = new FakeDbReader {
        override def getStoppedRuntimes: fs2.Stream[IO, Runtime] = Stream.emit(runtime)
      }
      val stoppedRuntimeChecker = StoppedRuntimeChecker.iml(dbReader, runtimeCheckerDeps)
      val res = stoppedRuntimeChecker.checkResource(runtime, dryRun)
      res.unsafeRunSync() shouldBe None
    }
  }

  it should "return Runtime if it is RUNNING in the cloud" in {
    implicit val arbA = Arbitrary(genRuntime(List("Stopped").toNel))

    forAll { (runtime: Runtime, dryRun: Boolean) =>
      val dbReader = new FakeDbReader {
        override def getStoppedRuntimes: fs2.Stream[IO, Runtime] = Stream.emit(runtime)
      }

      val computeService = new FakeGoogleComputeService {
        override def getInstance(project: GoogleProject, zone: ZoneName, instanceName: InstanceName)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[Instance]] = {
          val instance = Instance.newBuilder().setStatus(Instance.Status.RUNNING.toString).build()
          IO.pure(Some(instance))
        }

        override def stopInstance(project: GoogleProject, zone: ZoneName, instanceName: InstanceName)(implicit
          ev: Ask[IO, TraceId]
        ): IO[OperationFuture[Operation, Operation]] =
          if (dryRun) IO.raiseError(fail("this shouldn't be called")) else IO.pure(new FakeComputeOperationFuture())
      }

      val dataprocService = new BaseFakeGoogleDataprocService {
        override def getCluster(project: GoogleProject, region: RegionName, clusterName: DataprocClusterName)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[Cluster]] =
          IO.pure(Some(makeClusterWithStatus("RUNNING")))

        override def getClusterInstances(project: GoogleProject, region: RegionName, clusterName: DataprocClusterName)(
          implicit ev: Ask[IO, TraceId]
        ) =
          IO.pure(dataprocRoleZonePreemptibilityInstances)

        override def stopCluster(project: GoogleProject,
                                 region: RegionName,
                                 clusterName: DataprocClusterName,
                                 metadata: Option[Map[String, String]],
                                 isFullStop: Boolean
        )(implicit ev: Ask[IO, TraceId]): IO[Option[OperationFuture[Cluster, ClusterOperationMetadata]]] =
          if (dryRun) IO.raiseError(fail("this shouldn't be called"))
          else IO.pure(Some(new FakeDataprocClusterOperationFutureOp))
      }

      val azureVmService = new FakeAzureVmService {
        override def getAzureVm(name: InstanceName, cloudContext: AzureCloudContext)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[VirtualMachine]] =
          IO.pure(Some(mock[VirtualMachine]))

        override def stopAzureVm(name: InstanceName, cloudContext: AzureCloudContext)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[reactor.core.publisher.Mono[Void]]] =
          if (dryRun) IO.raiseError(fail("this shouldn't be called")) else IO.pure(None)
      }

      val runtimeCheckerDeps =
        initRuntimeCheckerDeps(googleComputeService = computeService,
                               googleDataprocService = dataprocService,
                               azureVmService = azureVmService
        )

      val stoppedRuntimeChecker = StoppedRuntimeChecker.iml(dbReader, runtimeCheckerDeps)
      val res = stoppedRuntimeChecker.checkResource(runtime, dryRun)
      // TODO: IA-3289 Implement StoppedRuntimeChecker for Azure VMs
      val expected = runtime.cloudContext.cloudProvider match {
        case CloudProvider.Gcp   => Some(runtime)
        case CloudProvider.Azure => None
      }

      res.unsafeRunSync() shouldBe expected
    }
  }
}

object StoppedRuntimeCheckerSpec {
  val defaultOperation = Operation.getDefaultInstance
  val defaultDataprocOperation = DataprocOperation(OperationName("op"), ClusterOperationMetadata.getDefaultInstance)
  val zone = ZoneName("us-central1-a")
  val dataprocRoleZonePreemptibilityInstances = Map(
    DataprocRoleZonePreemptibility(Master, zone, false) -> Set(InstanceName("master-instance")),
    DataprocRoleZonePreemptibility(Worker, zone, false) -> Set(InstanceName("worker-instance-0"),
                                                               InstanceName("worker-instance-1")
    ),
    DataprocRoleZonePreemptibility(SecondaryWorker, zone, true) -> Set(InstanceName("secondary-worker-instance-0"),
                                                                       InstanceName("secondary-worker-instance-1"),
                                                                       InstanceName("secondary-worker-instance-2")
    )
  )

  def makeClusterWithStatus(status: String): Cluster = {
    val validStatus = status match {
      case "RUNNING"  => "RUNNING"
      case "CREATING" => "CREATING"
      case "ERROR"    => "ERROR"
      case "DELETED"  => "DELETING"
    }
    Cluster.newBuilder().setStatus(ClusterStatus.newBuilder().setState(State.valueOf(validStatus))).build()
  }
}
