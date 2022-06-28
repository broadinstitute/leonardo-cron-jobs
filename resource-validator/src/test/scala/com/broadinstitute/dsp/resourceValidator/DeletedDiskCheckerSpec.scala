package com.broadinstitute.dsp
package resourceValidator

import cats.effect.IO
import cats.mtl.Ask
import com.broadinstitute.dsp.Generators._
import com.google.cloud.compute.v1
import com.google.cloud.compute.v1.Operation
import fs2.Stream
import org.broadinstitute.dsde.workbench.google2.mock.{FakeGoogleStorageInterpreter, MockGoogleDiskService}
import org.broadinstitute.dsde.workbench.google2.{DiskName, GoogleDiskService, ZoneName}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.scalatest.flatspec.AnyFlatSpec
import org.broadinstitute.dsde.workbench.openTelemetry.FakeOpenTelemetryMetricsInterpreter
import cats.effect.unsafe.implicits.global
import com.google.api.gax.longrunning.OperationFuture
class DeletedDiskCheckerSpec extends AnyFlatSpec with CronJobsTestSuite {
  it should "return None if disk no longer exists in Google" in {
    val diskService = new MockGoogleDiskService {
      override def getDisk(project: GoogleProject, zone: ZoneName, diskName: DiskName)(implicit
        ev: Ask[IO, TraceId]
      ): IO[Option[v1.Disk]] = IO.pure(None)
    }
    val checkerDeps =
      initDeletedDiskCheckerDeps(diskService)

    forAll { (disk: Disk, dryRun: Boolean) =>
      val dbReader = new FakeDbReader {
        override def getDeletedDisks: Stream[IO, Disk] = Stream.emit(disk)
      }
      val checker = DeletedDiskChecker.impl(dbReader, checkerDeps)
      val res = checker.checkResource(disk, dryRun)
      res.unsafeRunSync() shouldBe None
    }
  }

  it should "return disk if disk still exists in Google" in {
    forAll { (disk: Disk, dryRun: Boolean) =>
      val dbReader = new FakeDbReader {
        override def getDeletedDisks: Stream[IO, Disk] = Stream.emit(disk)
      }
      val diskService = new MockGoogleDiskService {
        override def getDisk(project: GoogleProject, zone: ZoneName, diskName: DiskName)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[v1.Disk]] = IO.pure(Some(v1.Disk.newBuilder.build()))

        override def deleteDisk(project: GoogleProject, zone: ZoneName, diskName: DiskName)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[OperationFuture[Operation, Operation]]] =
          if (dryRun) IO.raiseError(fail("this shouldn't be called")) else IO.pure(None)
      }
      val checkerDeps =
        initDeletedDiskCheckerDeps(diskService)

      val checker = DeletedDiskChecker.impl(dbReader, checkerDeps)
      val res = checker.checkResource(disk, dryRun)
      res.unsafeRunSync() shouldBe Some(disk)
    }
  }

  def initDeletedDiskCheckerDeps(
    googleDiskService: GoogleDiskService[IO] = MockGoogleDiskService
  ): DiskCheckerDeps[IO] = {
    val config = Config.appConfig.toOption.get
    val checkRunnerDeps =
      CheckRunnerDeps(config.reportDestinationBucket, FakeGoogleStorageInterpreter, FakeOpenTelemetryMetricsInterpreter)
    DiskCheckerDeps(checkRunnerDeps, googleDiskService)
  }
}
