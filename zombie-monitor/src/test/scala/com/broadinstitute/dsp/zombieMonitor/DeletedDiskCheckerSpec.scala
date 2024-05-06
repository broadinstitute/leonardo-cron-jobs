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

  val googleDisk: v1.Disk = com.google.cloud.compute.v1.Disk
    .newBuilder()
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

  it should "updateDiskStatus when GCP returns a 'billing is disabled' error (isDryRun = false)" in {
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
      verify(mockDbReader).updateDiskStatus(disk.id)
    }
  }
  it should "not updateDiskStatus when GCP returns a 'billing is disabled' error (isDryRun = true)" in {
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

      when(mockDbReader.updateDiskStatus(disk.id)).thenReturn(IO.unit)

      // Act
      val res = checker.checkResource(disk, isDryRun = true)
      res.unsafeRunSync()

      // Assert
      verify(mockDbReader, never()).updateDiskStatus(disk.id)
    }
  }

  it should "updateDiskStatus when GCP returns a 'compute engine has not been setup' error (isDryRun = false)" in {
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

      when(mockDbReader.updateDiskStatus(disk.id)).thenReturn(IO.unit)

      // Act
      val res = checker.checkResource(disk, isDryRun = false)
      res.unsafeRunSync()

      // Assert
      verify(mockDbReader).updateDiskStatus(disk.id)
    }
  }
  it should "not updateDiskStatus when GCP returns a 'compute engine has not been setup' error (isDryRun = true)" in {
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

      when(mockDbReader.updateDiskStatus(disk.id)).thenReturn(IO.unit)

      // Act
      val res = checker.checkResource(disk, isDryRun = true)
      res.unsafeRunSync()

      // Assert
      verify(mockDbReader, never()).updateDiskStatus(disk.id)
    }
  }

  it should "not updateDiskStatus when GCP returns an unhandled error (isDryRun = false)" in {
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

      when(mockDbReader.updateDiskStatus(disk.id)).thenReturn(IO.unit)

      // Act
      val res = checker.checkResource(disk, isDryRun = false)
      res.unsafeRunSync()

      // Assert
      verify(mockDbReader, never()).updateDiskStatus(disk.id)
    }
  }

  it should "updateDiskStatus when a call to GCP's getDisk endpoint returns no disk (isDryRun = false)" in {
    forAll { (disk: Disk) =>
      // Arrange
      setupMocks()
      when(
        mockGoogleDiskService.getDisk(any[GoogleProject], ZoneName(anyString()), DiskName(anyString()))(
          any[Ask[IO, TraceId]]
        )
      ).thenAnswer(_ => IO.pure(None))

      when(mockDbReader.updateDiskStatus(disk.id)).thenReturn(IO.unit)

      // Act
      val res = checker.checkResource(disk, isDryRun = false)
      res.unsafeRunSync()

      // Assert
      verify(mockDbReader).updateDiskStatus(disk.id)
    }
  }
  it should "not updateDiskStatus when a call to GCP's getDisk endpoint returns no disk (isDryRun = true)" in {
    forAll { (disk: Disk) =>
      // Arrange
      setupMocks()
      when(
        mockGoogleDiskService.getDisk(any[GoogleProject], ZoneName(anyString()), DiskName(anyString()))(
          any[Ask[IO, TraceId]]
        )
      ).thenAnswer(_ => IO.pure(None))

      when(mockDbReader.updateDiskStatus(disk.id)).thenReturn(IO.unit)

      // Act
      val res = checker.checkResource(disk, isDryRun = true)
      res.unsafeRunSync()

      // Assert
      verify(mockDbReader, never()).updateDiskStatus(disk.id)
    }
  }

  it should "not updateDiskStatus when a call to GCP's getDisk endpoint returns a disk (isDryRun = false)" in {
    forAll { (disk: Disk) =>
      // Arrange
      setupMocks()

      when(
        mockGoogleDiskService.getDisk(any[GoogleProject], ZoneName(anyString()), DiskName(anyString()))(
          any[Ask[IO, TraceId]]
        )
      ).thenAnswer(_ => IO.pure(Some(googleDisk)))

      when(mockDbReader.updateDiskStatus(disk.id)).thenReturn(IO.unit)

      // Act
      val res = checker.checkResource(disk, isDryRun = false)
      res.unsafeRunSync()

      // Assert
      verify(mockDbReader, never()).updateDiskStatus(disk.id)
    }
  }
  it should "not updateDiskStatus when a call to GCP's getDisk endpoint returns a disk (isDryRun = true)" in {
    forAll { (disk: Disk) =>
      // Arrange
      setupMocks()

      when(
        mockGoogleDiskService.getDisk(any[GoogleProject], ZoneName(anyString()), DiskName(anyString()))(
          any[Ask[IO, TraceId]]
        )
      ).thenAnswer(_ => IO.pure(Some(googleDisk)))

      when(mockDbReader.updateDiskStatus(disk.id)).thenReturn(IO.unit)

      // Act
      val res = checker.checkResource(disk, isDryRun = true)
      res.unsafeRunSync()

      // Assert
      verify(mockDbReader, never()).updateDiskStatus(disk.id)
    }
  }
}
