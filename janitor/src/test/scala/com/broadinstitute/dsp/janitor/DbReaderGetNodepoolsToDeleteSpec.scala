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
import com.broadinstitute.dsp.RemovableNodepoolStatus.removableStatuses
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
class DbReaderGetNodepoolsToDeleteSpec extends AnyFlatSpec with CronJobsTestSuite with IOChecker {
  implicit val config: DatabaseConfig = ConfigSpec.config.database
  implicit val transactor: Transactor[IO] = yoloTransactor

  val now = Instant.now()
  val gracePeriod = 3600 // in seconds

  val createdDateBeyondGracePeriod = now.minusSeconds(gracePeriod + 100)
  val createdDateWithinGracePeriod = now.minusSeconds(gracePeriod - 50)

  val destroyedDateBeyondGracePeriod = now.minusSeconds(gracePeriod + 200)
  val destroyedDateWithinGracePeriod = now.minusSeconds(gracePeriod - 150)

  it should s"detect for removal: Nodepool in status $removableStatuses status with app in DELETED status BEYOND grace period" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster, disk: Disk, removableStatuses: RemovableNodepoolStatus) =>
      val res = isolatedDbTest.use { _ =>
        val dbReader = DbReader.impl(transactor)

        for {
          diskId <- insertDisk(disk)
          clusterId <- insertK8sCluster(cluster, "RUNNING")
          _ <- insertNodepool(clusterId, "default-np", true)
          nodepoolId <- insertNodepool(clusterId, "np", false, removableStatuses.asString)
          namespaceId <- insertNamespace(clusterId, NamespaceName("ns"))
          _ <- insertApp(nodepoolId,
                         namespaceId,
                         "app",
                         diskId,
                         "DELETED",
                         createdDateWithinGracePeriod,
                         destroyedDateBeyondGracePeriod
          )

          nodepoolsToRemove <- dbReader.getNodepoolsToDelete.compile.toList
        } yield nodepoolsToRemove.map(_.nodepoolId) shouldBe List(nodepoolId)
      }
      res.unsafeRunSync()
    }
  }

  it should s"detect for removal: Nodepool in $removableStatuses status with app in ERROR status BEYOND grace period" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster, disk: Disk, removableStatuses: RemovableNodepoolStatus) =>
      val res = isolatedDbTest.use { _ =>
        val dbReader = DbReader.impl(transactor)

        for {
          diskId <- insertDisk(disk)
          clusterId <- insertK8sCluster(cluster, "RUNNING")
          _ <- insertNodepool(clusterId, "default-np", true)
          nodepoolId <- insertNodepool(clusterId, "np", false, removableStatuses.asString)
          namespaceId <- insertNamespace(clusterId, NamespaceName("ns"))
          _ <- insertApp(nodepoolId,
                         namespaceId,
                         "app",
                         diskId,
                         "ERROR",
                         createdDateBeyondGracePeriod,
                         destroyedDateWithinGracePeriod
          )
          nodepoolsToRemove <- dbReader.getNodepoolsToDelete.compile.toList
        } yield nodepoolsToRemove.map(_.nodepoolId) shouldBe List(nodepoolId)
      }
      res.unsafeRunSync()
    }
  }

  it should "not detect for removal: default nodepool" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster) =>
      val res = isolatedDbTest.use { _ =>
        val dbReader = DbReader.impl(transactor)

        for {
          clusterId <- insertK8sCluster(cluster, "RUNNING")
          _ <- insertNodepool(clusterId, "default-np", true)
          nodepoolsToRemove <- dbReader.getNodepoolsToDelete.compile.toList
        } yield nodepoolsToRemove.map(_.nodepoolId) shouldBe List.empty
      }
      res.unsafeRunSync()
    }
  }

  it should "NOT detect for removal: Nodepool in DELETED status" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster, disk: Disk) =>
      val res = isolatedDbTest.use { _ =>
        val dbReader = DbReader.impl(transactor)

        for {
          diskId <- insertDisk(disk)
          clusterId <- insertK8sCluster(cluster, "RUNNING")
          _ <- insertNodepool(clusterId, "default-np", true)
          nodepoolId <- insertNodepool(clusterId, "np", false, "DELETED")
          namespaceId <- insertNamespace(clusterId, NamespaceName("ns"))
          _ <- insertApp(nodepoolId,
                         namespaceId,
                         "app",
                         diskId,
                         "ERROR",
                         createdDateBeyondGracePeriod,
                         destroyedDateBeyondGracePeriod
          )
          nodepoolsToRemove <- dbReader.getNodepoolsToDelete.compile.toList
        } yield nodepoolsToRemove shouldBe List.empty
      }
      res.unsafeRunSync()
    }
  }

  it should s"NOT detect for removal: Nodepool in $removableStatuses status with app in RUNNING status" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster, disk: Disk, removableStatus: RemovableNodepoolStatus) =>
      val res = isolatedDbTest.use { _ =>
        val dbReader = DbReader.impl(transactor)

        for {
          diskId <- insertDisk(disk)
          clusterId <- insertK8sCluster(cluster, "RUNNING")
          _ <- insertNodepool(clusterId, "default-np", true)
          nodepoolId <- insertNodepool(clusterId, "np", false, removableStatus.asString)
          namespaceId <- insertNamespace(clusterId, NamespaceName("ns"))
          _ <- insertApp(nodepoolId,
                         namespaceId,
                         "app",
                         diskId,
                         "RUNNING",
                         createdDateBeyondGracePeriod,
                         destroyedDateBeyondGracePeriod
          )

          clustersToRemove <- dbReader.getKubernetesClustersToDelete.compile.toList
        } yield clustersToRemove shouldBe List.empty
      }
      res.unsafeRunSync()
    }
  }

  it should s"NOT detect for removal: Nodepool in $removableStatuses status with app in DELETED status WITHIN grace period" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster, disk: Disk, removableStatus: RemovableNodepoolStatus) =>
      val res = isolatedDbTest.use { _ =>
        val dbReader = DbReader.impl(transactor)

        for {
          diskId <- insertDisk(disk)
          clusterId <- insertK8sCluster(cluster, "RUNNING")
          _ <- insertNodepool(clusterId, "default-np", true)
          nodepoolId <- insertNodepool(clusterId, "np", false, removableStatus.asString)
          namespaceId <- insertNamespace(clusterId, NamespaceName("ns"))
          _ <- insertApp(nodepoolId,
                         namespaceId,
                         "app",
                         diskId,
                         "DELETED",
                         createdDateBeyondGracePeriod,
                         destroyedDateWithinGracePeriod
          )

          clustersToRemove <- dbReader.getKubernetesClustersToDelete.compile.toList
        } yield clustersToRemove shouldBe List.empty
      }
      res.unsafeRunSync()
    }
  }

  it should s"NOT detect for removal: Nodepool in $removableStatuses status with app in ERROR status WITHIN grace period" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster, disk: Disk, removableStatus: RemovableNodepoolStatus) =>
      val res = isolatedDbTest.use { _ =>
        val dbReader = DbReader.impl(transactor)

        for {
          diskId <- insertDisk(disk)
          clusterId <- insertK8sCluster(cluster, "RUNNING")
          _ <- insertNodepool(clusterId, "default-np", true)
          nodepoolId <- insertNodepool(clusterId, "np", false, removableStatus.asString)
          namespaceId <- insertNamespace(clusterId, NamespaceName("ns"))
          _ <- insertApp(nodepoolId,
                         namespaceId,
                         "app",
                         diskId,
                         "ERROR",
                         createdDateWithinGracePeriod,
                         destroyedDateBeyondGracePeriod
          )
          clustersToRemove <- dbReader.getKubernetesClustersToDelete.compile.toList
        } yield clustersToRemove shouldBe List.empty
      }
      res.unsafeRunSync()
    }
  }
}
