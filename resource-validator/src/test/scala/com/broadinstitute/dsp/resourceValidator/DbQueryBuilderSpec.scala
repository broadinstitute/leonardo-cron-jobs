package com.broadinstitute.dsp
package resourceValidator

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

  it should "build deletedDisksQuery properly" taggedAs DbTest in {
    check(DbReader.shouldBedeletedDisksQuery)
  }

  it should "build initBucketsToDeleteQuery properly" taggedAs DbTest in {
    check(DbReader.initBucketsToDeleteQuery)
  }

  it should "build deletedRuntimeQuery properly" taggedAs DbTest in {
    check(DbReader.deletedRuntimeQuery)
  }

  it should "build erroredRuntimeQuery properly" taggedAs DbTest in {
    check(DbReader.erroredRuntimeQuery)
  }

  it should "build stoppedRuntimeQuery properly" taggedAs DbTest in {
    check(DbReader.stoppedRuntimeQuery)
  }

  it should "build deletedAndErroredKubernetesClusterQuery properly" taggedAs DbTest in {
    check(DbReader.deletedAndErroredKubernetesClusterQuery)
  }

  it should "build deletedAndErroredNodepoolQuery properly" taggedAs DbTest in {
    check(DbReader.deletedAndErroredNodepoolQuery)
  }

  // This test will fail because region is an optional field, and we're trying to read it as non optional field.
  // But this is okay in reality because if we're reading it as `Dataproc` runtime, region should always exist;
  // and we should get a runtime exception if there's a polluted data in DB.
  it should "build dataprocClusterWithWorkersQuery properly" ignore {
    check(DbReader.dataprocClusterWithWorkersQuery)
  }
}
