package com.broadinstitute.dsp
package zombieMonitor

import org.scalatest.flatspec.AnyFlatSpec

class DeletedDiskCheckerSpec extends AnyFlatSpec with CronJobsTestSuite {
  it should "return None if disk no longer exists in Google" in {
    true shouldBe false
  }

}
