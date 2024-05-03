package com.broadinstitute.dsp
package zombieMonitor

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.mtl.Ask
import com.broadinstitute.dsp.Generators._
import com.google.api.gax.grpc.GrpcStatusCode
import com.google.api.gax.rpc.{InternalException, PermissionDeniedException}
import com.google.cloud.compute.v1
import io.grpc.Status
import org.broadinstitute.dsde.workbench.google2.{DiskName, GoogleDiskService, ZoneName}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.mockito.ArgumentMatchers.{any, anyLong, anyString}
import org.mockito.Mockito.{mock, never, verify, when}
import org.scalatest.flatspec.AnyFlatSpec

class DeletedDiskCheckerSpec extends AnyFlatSpec with CronJobsTestSuite {

  var mockDbReader: DbReader[IO] = _
  var mockCheckRunnerDeps: CheckRunnerDeps[IO] = _
  var mockGoogleDiskService: GoogleDiskService[IO] = _
  var mockDiskCheckerDeps: DiskCheckerDeps[IO] = _
  var checker: CheckRunner[IO, Disk] = _

  var billingDisabledException = new PermissionDeniedException(
    new Exception("This API method requires billing to be enabled"),
    GrpcStatusCode.of(Status.Code.PERMISSION_DENIED),
    false
  )
  var computeEngineNotSetupException = new PermissionDeniedException(
    new Exception("Compute Engine API has not been used"),
    GrpcStatusCode.of(Status.Code.PERMISSION_DENIED),
    false
  )

  val googleDisk: v1.Disk = com.google.cloud.compute.v1.Disk.newBuilder()
    .setName("disk-name")
    .setSizeGb(100)
    .setType("pd-standard")
    .build()
  def setupMocks(): Unit = {
    mockDbReader = mock(classOf[DbReader[IO]])
    mockCheckRunnerDeps = mock(classOf[CheckRunnerDeps[IO]])
    mockGoogleDiskService = mock(classOf[GoogleDiskService[IO]])
    mockDiskCheckerDeps = DiskCheckerDeps(mockCheckRunnerDeps, mockGoogleDiskService)
    checker = DeletedDiskChecker.impl(mockDbReader, mockDiskCheckerDeps)
  }

  it should "call updateDiskStatus when billing is disabled" in {
    forAll { (disk: Disk) =>
      // Arrange
      setupMocks()
      when(
        mockGoogleDiskService.getDisk(any[GoogleProject], ZoneName(anyString()), DiskName(anyString()))(
          any[Ask[IO, TraceId]]
        )
      ).thenAnswer(_ =>
        IO.raiseError(
          billingDisabledException
        )
      )

      when(mockDbReader.updateDiskStatus(anyLong())).thenReturn(IO.unit)

      // Act
      val res = checker.checkResource(disk, isDryRun = false)
      res.unsafeRunSync()

      // Assert
      verify(mockDbReader).updateDiskStatus(anyLong())
    }
  }

  it should "call updateDiskStatus when compute engine has not been setup" in {
    forAll { (disk: Disk) =>
      // Arrange
      setupMocks()
      when(
        mockGoogleDiskService.getDisk(any[GoogleProject], ZoneName(anyString()), DiskName(anyString()))(
          any[Ask[IO, TraceId]]
        )
      ).thenAnswer(_ =>
        IO.raiseError(
          computeEngineNotSetupException
        )
      )

      when(mockDbReader.updateDiskStatus(anyLong())).thenReturn(IO.unit)

      // Act
      val res = checker.checkResource(disk, isDryRun = false)
      res.unsafeRunSync()

      // Assert
      verify(mockDbReader).updateDiskStatus(anyLong())
    }
  }

  it should "not call updateDiskStatus when unexpected exception is thrown" in {
    forAll { (disk: Disk) =>
      // Arrange
      setupMocks()
      when(
        mockGoogleDiskService.getDisk(any[GoogleProject], ZoneName(anyString()), DiskName(anyString()))(
          any[Ask[IO, TraceId]]
        )
      ).thenAnswer(_ =>
        IO.raiseError(
          new InternalException(new Exception("Something unexpected happened"),
                                GrpcStatusCode.of(Status.Code.PERMISSION_DENIED),
                                false
          )
        )
      )

      when(mockDbReader.updateDiskStatus(anyLong())).thenReturn(IO.unit)

      // Act
      val res = checker.checkResource(disk, isDryRun = false)
      res.unsafeRunSync()

      // Assert
      verify(mockDbReader, never()).updateDiskStatus(anyLong())
    }
  }

  it should "not call updateDiskStatus when billing is disabled and run in dryRun mode" in {
    forAll { (disk: Disk) =>
      // Arrange
      setupMocks()
      when(
        mockGoogleDiskService.getDisk(any[GoogleProject], ZoneName(anyString()), DiskName(anyString()))(
          any[Ask[IO, TraceId]]
        )
      ).thenAnswer(_ =>
        IO.raiseError(
          billingDisabledException
        )
      )

      when(mockDbReader.updateDiskStatus(anyLong())).thenReturn(IO.unit)

      // Act
      val res = checker.checkResource(disk, isDryRun = true)
      res.unsafeRunSync()

      // Assert
      verify(mockDbReader, never()).updateDiskStatus(anyLong())
    }
  }

  it should "not call updateDiskStatus when compute engine has not been setup and run in dryRun mode" in {
    forAll { (disk: Disk) =>
      // Arrange
      setupMocks()
      when(
        mockGoogleDiskService.getDisk(any[GoogleProject], ZoneName(anyString()), DiskName(anyString()))(
          any[Ask[IO, TraceId]]
        )
      ).thenAnswer(_ =>
        IO.raiseError(
          computeEngineNotSetupException
        )
      )

      when(mockDbReader.updateDiskStatus(anyLong())).thenReturn(IO.unit)

      // Act
      val res = checker.checkResource(disk, isDryRun = true)
      res.unsafeRunSync()

      // Assert
      verify(mockDbReader, never()).updateDiskStatus(anyLong())
    }
  }

  it should "not call updateDiskStatus when getDisk returns no disk in dryRun mode" in {
    forAll { (disk: Disk) =>
      // Arrange
      setupMocks()
      when(
        mockGoogleDiskService.getDisk(any[GoogleProject], ZoneName(anyString()), DiskName(anyString()))(
          any[Ask[IO, TraceId]]
        )
      ).thenAnswer(_ => IO.pure(None))

      when(mockDbReader.updateDiskStatus(anyLong())).thenReturn(IO.unit)

      // Act
      val res = checker.checkResource(disk, isDryRun = true)
      res.unsafeRunSync()

      // Assert
      verify(mockDbReader, never()).updateDiskStatus(anyLong())
    }
  }
  it should "not call updateDiskStatus when getDisk returns a disk in dryRun mode" in {
    forAll { (disk: Disk) =>
      // Arrange
      setupMocks()

      when(
        mockGoogleDiskService.getDisk(any[GoogleProject], ZoneName(anyString()), DiskName(anyString()))(
          any[Ask[IO, TraceId]]
        )
      ).thenAnswer(_ => IO.pure(Some(googleDisk)))

      when(mockDbReader.updateDiskStatus(anyLong())).thenReturn(IO.unit)

      // Act
      val res = checker.checkResource(disk, isDryRun = true)
      res.unsafeRunSync()

      // Assert
      verify(mockDbReader, never()).updateDiskStatus(anyLong())
    }
  }


  it should "call updateDiskStatus when getDisk returns no disk" in {
    forAll { (disk: Disk) =>
      // Arrange
      setupMocks()
      when(
        mockGoogleDiskService.getDisk(any[GoogleProject], ZoneName(anyString()), DiskName(anyString()))(
          any[Ask[IO, TraceId]]
        )
      ).thenAnswer(_ => IO.pure(None))

      when(mockDbReader.updateDiskStatus(anyLong())).thenReturn(IO.unit)

      // Act
      val res = checker.checkResource(disk, isDryRun = false)
      res.unsafeRunSync()

      // Assert
      verify(mockDbReader).updateDiskStatus(anyLong())
    }
  }
  it should "not call updateDiskStatus when getDisk returns a disk" in {
    forAll { (disk: Disk) =>
      // Arrange
      setupMocks()

      when(
        mockGoogleDiskService.getDisk(any[GoogleProject], ZoneName(anyString()), DiskName(anyString()))(
          any[Ask[IO, TraceId]]
        )
      ).thenAnswer(_ => IO.pure(Some(googleDisk)))

      when(mockDbReader.updateDiskStatus(anyLong())).thenReturn(IO.unit)

      // Act
      val res = checker.checkResource(disk, isDryRun = false)
      res.unsafeRunSync()

      // Assert
      verify(mockDbReader, never()).updateDiskStatus(anyLong())
    }
  }
}
