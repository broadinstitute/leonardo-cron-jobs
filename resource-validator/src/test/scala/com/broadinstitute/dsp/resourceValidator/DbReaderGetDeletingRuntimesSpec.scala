package com.broadinstitute.dsp
package resourceValidator

import cats.effect.unsafe.implicits.global
import com.broadinstitute.dsp.Generators._
import com.broadinstitute.dsp.DbTestHelper.{insertRuntime, insertRuntimeConfig, transactorResource, yoloTransactor}
import doobie.scalatest.IOChecker
import org.scalatest.flatspec.AnyFlatSpec

import java.time.Instant
import java.time.temporal.ChronoUnit

class DbReaderGetDeletingRuntimesSpec extends AnyFlatSpec with CronJobsTestSuite with IOChecker {
  implicit val config: DatabaseConfig = ConfigSpec.config.database
  val transactor = yoloTransactor

  it should "return runtimes that have been deleting for over an hour in the Leo DB" taggedAs DbTest in {
    forAll { (rt: Runtime) =>
      val runtime = Runtime.setStatus(rt, "Deleting")
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

        val oldTimeStamp = Instant.now.minus(2, ChronoUnit.HOURS)
        for {
          runtimeConfigId <- insertRuntimeConfig(runtime.cloudService, dateAccessed = oldTimeStamp)
          id <- insertRuntime(runtime, runtimeConfigId, dateAccessed = oldTimeStamp)
          runtimes <- dbReader.getDeletingRuntimes.compile.toList
        } yield runtimes should contain theSameElementsAs List(Runtime.setId(runtime, id))
      }
      res.unsafeRunSync()
    }
  }

  it should "not return runtimes that have been deleting within the past hour" taggedAs DbTest in {
    forAll { (rt: Runtime) =>
      val runtime = Runtime.setStatus(rt, "Deleting")
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

        for {
          runtimeConfigId <- insertRuntimeConfig(runtime.cloudService)
          id <- insertRuntime(runtime, runtimeConfigId)
          runtimes <- dbReader.getDeletingRuntimes.compile.toList
        } yield runtimes shouldBe List.empty
      }
      res.unsafeRunSync()
    }
  }
}
