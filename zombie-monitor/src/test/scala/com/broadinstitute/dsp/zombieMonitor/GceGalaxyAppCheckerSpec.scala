package com.broadinstitute.dsp
package zombieMonitor

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.mtl.Ask
import com.google.cloud.compute.v1.Instance
import org.broadinstitute.dsde.workbench.google2.{GoogleComputeService, ZoneName}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.util2.InstanceName
import org.mockito.ArgumentMatchers.{any, anyLong, anyString}
import org.mockito.Mockito.{mock, never, times, verify, when}
import org.scalatest.flatspec.AnyFlatSpec

class GceGalaxyAppCheckerSpec extends AnyFlatSpec with CronJobsTestSuite {
  val app = GceGalaxyApp(42L, "my-galaxy", GoogleProject("project1"), ZoneName("us-central1-a"))

  private def setup(vm: Option[Instance]): (DbReader[IO], CheckRunner[IO, GceGalaxyApp]) = {
    val dbReader = mock(classOf[DbReader[IO]])
    val computeService = mock(classOf[GoogleComputeService[IO]])
    when(
      computeService.getInstance(any[GoogleProject], ZoneName(anyString()), InstanceName(anyString()))(
        any[Ask[IO, TraceId]]
      )
    ).thenReturn(IO.pure(vm))
    when(dbReader.markGalaxyAppDeleted(anyLong())).thenReturn(IO.unit)
    val deps = GceGalaxyAppCheckerDeps(mock(classOf[CheckRunnerDeps[IO]]), computeService)
    (dbReader, GceGalaxyAppChecker.impl(dbReader, deps))
  }

  it should "mark the app deleted when its VM is gone (isDryRun = false)" in {
    val (dbReader, checker) = setup(None)

    val res = checker.checkResource(app, isDryRun = false).unsafeRunSync()

    res shouldBe Some(app)
    verify(dbReader, times(1)).markGalaxyAppDeleted(app.appId)
  }

  it should "report the app but not change the DB when its VM is gone (isDryRun = true)" in {
    val (dbReader, checker) = setup(None)

    val res = checker.checkResource(app, isDryRun = true).unsafeRunSync()

    res shouldBe Some(app)
    verify(dbReader, never()).markGalaxyAppDeleted(anyLong())
  }

  it should "do nothing when the VM exists" in {
    val (dbReader, checker) = setup(Some(Instance.newBuilder().setName("galaxy-my-galaxy").build()))

    val res = checker.checkResource(app, isDryRun = false).unsafeRunSync()

    res shouldBe None
    verify(dbReader, never()).markGalaxyAppDeleted(anyLong())
  }
}
