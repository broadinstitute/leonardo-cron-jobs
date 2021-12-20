package com.broadinstitute.dsp
package resourceValidator

import cats.effect.IO
import cats.mtl.Ask
import com.broadinstitute.dsp.Generators._
import com.broadinstitute.dsp.resourceValidator.InitDependenciesHelper._
import com.google.cloud.compute.v1.{Instance, Operation}
import com.google.cloud.dataproc.v1.ClusterStatus.State
import com.google.cloud.dataproc.v1.{Cluster, ClusterOperationMetadata, ClusterStatus}
import fs2.Stream
import org.broadinstitute.dsde.workbench.google2.DataprocRole.{Master, SecondaryWorker, Worker}
import org.broadinstitute.dsde.workbench.google2.mock.{BaseFakeGoogleDataprocService, FakeGoogleComputeService}
import org.broadinstitute.dsde.workbench.google2.{DataprocClusterName, DataprocOperation, DataprocRoleZonePreemptibility, InstanceName, OperationName, RegionName, ZoneName}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import com.broadinstitute.dsp.resourceValidator.StoppedRuntimeCheckerSpec._
import org.scalatest.flatspec.AnyFlatSpec
import cats.effect.unsafe.implicits.global

final class StoppedRuntimeCheckerSpec extends AnyFlatSpec with CronJobsTestSuite {
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
        override def getStoppedRuntimes: fs2.Stream[IO, Runtime] = Stream.emit(runtime)
      }
      val stoppedRuntimeChecker = StoppedRuntimeChecker.iml(dbReader, runtimeCheckerDeps)
      val res = stoppedRuntimeChecker.checkResource(runtime, dryRun)
      res.unsafeRunSync() shouldBe None
    }
  }

  it should "return Runtime if it is RUNNING in Google" in {
    forAll { (runtime: Runtime, dryRun: Boolean) =>
      val dbReader = new FakeDbReader {
        override def getStoppedRuntimes: fs2.Stream[IO, Runtime] = Stream.emit(runtime)
      }

      val computeService = new FakeGoogleComputeService {
        override def getInstance(project: GoogleProject, zone: ZoneName, instanceName: InstanceName)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[Instance]] = {
          val instance = Instance.newBuilder().setStatus(Instance.Status.valueOf(runtime.status.toUpperCase)).build()
          IO.pure(Some(instance))
        }

        override def stopInstance(project: GoogleProject, zone: ZoneName, instanceName: InstanceName)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Operation] = if (dryRun) IO.raiseError(fail("this shouldn't be called")) else IO.pure(defaultOperation)
      }

      val dataprocService = new BaseFakeGoogleDataprocService {
        override def getCluster(project: GoogleProject, region: RegionName, clusterName: DataprocClusterName)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[Cluster]] =
          IO.pure(Some(makeClusterWithStatus(runtime.status.toUpperCase)))

        override def getClusterInstances(project: GoogleProject, region: RegionName, clusterName: DataprocClusterName)(
          implicit ev: Ask[IO, TraceId]
        ) =
          IO.pure(dataprocRoleZonePreemptibilityInstances)

        override def stopCluster(project: GoogleProject, region: RegionName, clusterName: DataprocClusterName, metadata: Option[Map[String, String]], isFullStop: Boolean)(implicit ev: Ask[IO, TraceId]): IO[Option[DataprocOperation]] =
          if (dryRun) IO.raiseError(fail("this shouldn't be called")) else IO.pure(Some(defaultDataprocOperation))
      }

      val runtimeCheckerDeps =
        initRuntimeCheckerDeps(googleComputeService = computeService, googleDataprocService = dataprocService)

      val stoppedRuntimeChecker = StoppedRuntimeChecker.iml(dbReader, runtimeCheckerDeps)
      val res = stoppedRuntimeChecker.checkResource(runtime, dryRun)
      val expectedRes =
        if (runtime.status.toUpperCase == "RUNNING") Some(runtime)
        else None

      res.unsafeRunSync() shouldBe expectedRes

    }
  }

  it should "return None if Dataproc cluster is RUNNING but its instances are STOPPED" in {
    implicit val arbA = arbDataprocRuntime
    forAll { (runtime: Runtime.Dataproc, dryRun: Boolean) =>
      val dbReader = new FakeDbReader {
        override def getStoppedRuntimes: fs2.Stream[IO, Runtime] = Stream.emit(runtime)
      }

      val computeService = new FakeGoogleComputeService {
        override def getInstance(project: GoogleProject, zone: ZoneName, instanceName: InstanceName)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[Instance]] = {
          val instance = Instance.newBuilder().setStatus(Instance.Status.STOPPED).build()
          IO.pure(Some(instance))
        }

        override def stopInstance(project: GoogleProject, zone: ZoneName, instanceName: InstanceName)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Operation] = if (dryRun) IO.raiseError(fail("this shouldn't be called")) else IO.pure(defaultOperation)
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

        override def stopCluster(project: GoogleProject, region: RegionName, clusterName: DataprocClusterName, metadata: Option[Map[String, String]], isFullStop: Boolean)(implicit ev: Ask[IO, TraceId]): IO[Option[DataprocOperation]] =
          if (dryRun) IO.raiseError(fail("this shouldn't be called")) else IO.pure(Some(defaultDataprocOperation))
      }

      val runtimeCheckerDeps =
        initRuntimeCheckerDeps(googleComputeService = computeService, googleDataprocService = dataprocService)

      val stoppedRuntimeChecker = StoppedRuntimeChecker.iml(dbReader, runtimeCheckerDeps)
      val res = stoppedRuntimeChecker.checkResource(runtime, dryRun)
      res.unsafeRunSync() shouldBe None
    }
  }
}

object StoppedRuntimeCheckerSpec {
  val defaultOperation = Operation.getDefaultInstance
  val defaultDataprocOperation = new DataprocOperation(OperationName("op"), ClusterOperationMetadata.getDefaultInstance)
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
