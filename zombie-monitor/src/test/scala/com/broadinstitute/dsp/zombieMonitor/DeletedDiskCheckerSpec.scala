package com.broadinstitute.dsp
package zombieMonitor

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.mtl.Ask
import com.broadinstitute.dsp.Generators._
import com.google.api.gax.grpc.GrpcStatusCode
import com.google.api.gax.rpc.PermissionDeniedException
import io.grpc.Status
import org.broadinstitute.dsde.workbench.google2.{DiskName, GoogleDiskService, ZoneName}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.mockito.ArgumentMatchers.{any, anyLong, anyString}
import org.mockito.Mockito.{mock, verify, when}
import org.scalatest.flatspec.AnyFlatSpec

class DeletedDiskCheckerSpec extends AnyFlatSpec with CronJobsTestSuite {
  it should "call updateDiskStatus when billing is disabled" in {

    forAll { (disk: Disk) =>
      //Arrange
      val mockDbReader = mock(classOf[DbReader[IO]])
      val mockCheckRunnerDeps = mock(classOf[CheckRunnerDeps[IO]])
      val mockGoogleDiskService = mock(classOf[GoogleDiskService[IO]])
      val mockDiskCheckerDeps = DiskCheckerDeps(mockCheckRunnerDeps, mockGoogleDiskService)
      val checker = DeletedDiskChecker.impl(mockDbReader, mockDiskCheckerDeps)

      when(mockGoogleDiskService.getDisk(any[GoogleProject], ZoneName(anyString()), DiskName(anyString()))(
        any[Ask[IO, TraceId]]
      )).thenAnswer(_ =>
        IO.raiseError(new PermissionDeniedException(new Exception("This API method requires billing to be enabled"),GrpcStatusCode.of(Status.Code.PERMISSION_DENIED),false)))

      when(mockDbReader.updateDiskStatus(anyLong())).thenReturn(IO.unit)

      //Act
      val res = checker.checkResource(disk, isDryRun = false)
      res.unsafeRunSync()

      //Assert
      verify(mockDbReader).updateDiskStatus(anyLong())
      verify(mockGoogleDiskService).getDisk(any[GoogleProject], ZoneName(anyString()), DiskName(anyString()))(
        any[Ask[IO, TraceId]]
      )
    }
  }
}
