package com.broadinstitute.dsp.resourceValidator

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.broadinstitute.dsp.DbTestHelper.{insertDisk, isolatedDbTest, yoloTransactor}
import com.broadinstitute.dsp.Generators.genDisk
import com.broadinstitute.dsp._
import doobie.Transactor
import doobie.scalatest.IOChecker
import org.scalatest.flatspec.AnyFlatSpec

class DbReaderGetDeleteDiskCandidatesSpec extends AnyFlatSpec with CronJobsTestSuite with IOChecker {
  implicit val config: DatabaseConfig = ConfigSpec.config.database
  implicit val transactor: Transactor[IO] = yoloTransactor

  it should "return disks that have been deleted without destroyedDate for over an hour in the Leo DB" taggedAs DbTest in {
    val disk = genDisk.sample.get
    val res = isolatedDbTest.use { _ =>
      val dbReader = DbReader.impl(transactor)

      for {
        _ <- insertDisk(disk, "Deleted", Some(DbTransactor.leonardoDummyDate))
        disks <- dbReader.getDeletedDisks.compile.toList
      } yield disks.map(_.diskName).contains(disk.diskName) shouldBe true
    }
    res.unsafeRunSync()
  }
}
