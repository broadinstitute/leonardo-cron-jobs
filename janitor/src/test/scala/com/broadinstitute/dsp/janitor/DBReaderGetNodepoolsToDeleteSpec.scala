package com.broadinstitute.dsp
package janitor

import com.broadinstitute.dsp.DBTestHelper.{
  insertApp,
  insertDisk,
  insertK8sCluster,
  insertNamespace,
  insertNodepool,
  transactorResource,
  yoloTransactor
}
import com.broadinstitute.dsp.Generators._
import com.broadinstitute.dsp.RemovableNodepoolStatus.removableStatuses
import doobie.scalatest.IOChecker
import org.broadinstitute.dsde.workbench.google2.GKEModels.KubernetesClusterId
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
class DBReaderGetNodepoolsToDeleteSpec extends AnyFlatSpec with CronJobsTestSuite with IOChecker {
  implicit val config = ConfigSpec.config.database
  val transactor = yoloTransactor

  val now = Instant.now()
  val gracePeriod = 3600 // in seconds

  val createdDateBeyondGracePeriod = now.minusSeconds(gracePeriod + 100)
  val createdDateWithinGracePeriod = now.minusSeconds(gracePeriod - 50)

  val destroyedDateBeyondGracePeriod = now.minusSeconds(gracePeriod + 200)
  val destroyedDateWithinGracePeriod = now.minusSeconds(gracePeriod - 150)

  it should s"detect for removal: Nodepool in status $removableStatuses status with app in DELETED status BEYOND grace period" taggedAs DbTest in {
    forAll { (cluster: KubernetesClusterId, disk: Disk, removableStatuses: RemovableNodepoolStatus) =>
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

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
    forAll { (cluster: KubernetesClusterId, disk: Disk, removableStatuses: RemovableNodepoolStatus) =>
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

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
    forAll { (cluster: KubernetesClusterId) =>
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

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
    forAll { (cluster: KubernetesClusterId, disk: Disk) =>
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

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
    forAll { (cluster: KubernetesClusterId, disk: Disk, removableStatus: RemovableNodepoolStatus) =>
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

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
    forAll { (cluster: KubernetesClusterId, disk: Disk, removableStatus: RemovableNodepoolStatus) =>
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

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
    forAll { (cluster: KubernetesClusterId, disk: Disk, removableStatus: RemovableNodepoolStatus) =>
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

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
