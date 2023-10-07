package com.broadinstitute.dsp
package janitor

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.broadinstitute.dsp.DbTestHelper.{
  insertApp,
  insertDisk,
  insertK8sCluster,
  insertNamespace,
  insertNodepool,
  isolatedDbTest,
  yoloTransactor
}
import com.broadinstitute.dsp.Generators._
import doobie.Transactor
import doobie.scalatest.IOChecker
import org.broadinstitute.dsde.workbench.google2.KubernetesSerializableName.NamespaceName
import org.scalatest.flatspec.AnyFlatSpec

import java.time.Instant

/**
 * Not running these tests in CI yet since we'll need to set up mysql container and Leonardo tables in CI. Punt for now
 * For running these tests locally, you can
 *   - Start leonardo mysql container locally
 *   - Run a Leonardo database unit test (e.g. ClusterComponentSpec)
 *   - Run this spec
 */
class DbReaderGetAppStuckToReportSpec extends AnyFlatSpec with CronJobsTestSuite with IOChecker {
  implicit val config: DatabaseConfig = ConfigSpec.config.database
  implicit val transactor: Transactor[IO] = yoloTransactor

  val now = Instant.now()
  val gracePeriod_deleting = 3600 // in seconds
  val gracePeriod_creating = 7200 // in seconds

  it should "detect for reporting: App in DELETING or CREATING status BEYOND grace period" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster, disk: Disk) =>
      val res = isolatedDbTest.use { _ =>
        val dbReader = DbReader.impl(transactor)

        for {
          diskId <- insertDisk(disk)
          clusterId <- insertK8sCluster(cluster, "RUNNING")
          _ <- insertNodepool(clusterId, "default-np", true)
          nodepoolId <- insertNodepool(clusterId, "np", false, "RUNNING")
          namespaceId <- insertNamespace(clusterId, NamespaceName("ns"))
          appId_deleting <- insertApp(nodepoolId,
                                      namespaceId,
                                      "app_deleting",
                                      diskId,
                                      "DELETING",
                                      now.minusSeconds(gracePeriod_deleting + 100),
                                      now.minusSeconds(gracePeriod_deleting + 200)
          )
          appId_creating <- insertApp(nodepoolId,
                                      namespaceId,
                                      "app_creating",
                                      diskId,
                                      "PROVISIONING",
                                      now.minusSeconds(gracePeriod_creating + 100),
                                      now.minusSeconds(gracePeriod_creating + 200)
          )

          appsToReport <- dbReader.getStuckAppToReport.compile.toList
        } yield appsToReport.map(_.id) shouldBe List(appId_deleting, appId_creating)
      }
      res.unsafeRunSync()
    }
  }

  it should "not detect for reporting: App in DELETING or CREATING status WITHIN grace period" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster, disk: Disk) =>
      val res = isolatedDbTest.use { _ =>
        val dbReader = DbReader.impl(transactor)

        for {
          diskId <- insertDisk(disk)
          clusterId <- insertK8sCluster(cluster, "RUNNING")
          _ <- insertNodepool(clusterId, "default-np", true)
          nodepoolId <- insertNodepool(clusterId, "np", false, "RUNNING")
          namespaceId <- insertNamespace(clusterId, NamespaceName("ns"))
          _ <- insertApp(nodepoolId,
                         namespaceId,
                         "app_deleting",
                         diskId,
                         "DELETING",
                         now.minusSeconds(gracePeriod_deleting - 100),
                         now.minusSeconds(gracePeriod_deleting + 200)
          )
          _ <- insertApp(nodepoolId,
                         namespaceId,
                         "app_creating",
                         diskId,
                         "PROVISIONING",
                         now.minusSeconds(gracePeriod_creating - 100),
                         now.minusSeconds(gracePeriod_creating + 200)
          )

          appsToReport <- dbReader.getStuckAppToReport.compile.toList
        } yield appsToReport.map(_.id) shouldBe List.empty
      }
      res.unsafeRunSync()
    }
  }
}
