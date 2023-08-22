package com.broadinstitute.dsp
package janitor

import com.broadinstitute.dsp.DbTestHelper._
import doobie.scalatest.IOChecker
import org.scalatest.flatspec.AnyFlatSpec

/**
 * Not running these tests in CI yet since we'll need to set up mysql container and Leonardo tables in CI. Punt for now
 * For running these tests locally, you can
 *   - Start leonardo mysql container locally
 *   - Run a Leonardo database unit test (e.g. ClusterComponentSpec)
 *   - Run this spec
 */
final class DbQueryBuilderSpec extends AnyFlatSpec with CronJobsTestSuite with IOChecker {
  implicit val config: DatabaseConfig = ConfigSpec.config.database
  val transactor = yoloTransactor

  it should "build kubernetesClustersToDeleteQuery properly" taggedAs DbTest in {
    check(DbReader.kubernetesClustersToDeleteQuery)
  }

  it should "build applessNodepoolQuery properly" taggedAs DbTest in {
    check(DbReader.applessNodepoolQuery)
  }

  it should "build stagingBucketsToDeleteQuery properly" taggedAs DbTest in {
    check(DbReader.stagingBucketsToDeleteQuery)
  }

  it should "build appStuckQuery properly" taggedAs DbTest in {
    check(DbReader.appStuckQuery)
  }
}
