package com.broadinstitute.dsp
package janitor

import cats.effect.unsafe.implicits.global
import com.broadinstitute.dsp.DbTestHelper._
import com.broadinstitute.dsp.Generators._
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
final class DbReaderGetKubernetesClustersToDeleteSpec extends AnyFlatSpec with CronJobsTestSuite with IOChecker {
  implicit val config = ConfigSpec.config.database
  val transactor = yoloTransactor

  val now = Instant.now()
  val gracePeriod = 3600 // in seconds

  val createdDateBeyondGracePeriod = now.minusSeconds(gracePeriod + 100)
  val createdDateWithinGracePeriod = now.minusSeconds(gracePeriod - 50)

  val destroyedDateBeyondGracePeriod = now.minusSeconds(gracePeriod + 200)
  val destroyedDateWithinGracePeriod = now.minusSeconds(gracePeriod - 150)

  it should "detect for removal: Kubernetes cluster in RUNNING status with app in DELETED status BEYOND grace period" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster, disk: Disk) =>
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

        for {
          diskId <- insertDisk(disk)
          clusterId <- insertK8sCluster(cluster, "RUNNING")
          _ <- insertNodepool(clusterId, "default-np", true)
          nodepoolId <- insertNodepool(clusterId, "np", false)
          namespaceId <- insertNamespace(clusterId, NamespaceName("ns"))
          _ <- insertApp(nodepoolId,
                         namespaceId,
                         "app",
                         diskId,
                         "DELETED",
                         createdDateWithinGracePeriod,
                         destroyedDateBeyondGracePeriod
          )

          clustersToRemove <- dbReader.getKubernetesClustersToDelete.compile.toList
        } yield clustersToRemove.map(_.id) shouldBe List(clusterId)
      }
      res.unsafeRunSync()
    }
  }

  it should "detect for removal: Kubernetes cluster in RUNNING status with app in ERROR status BEYOND grace period" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster, disk: Disk) =>
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

        for {
          diskId <- insertDisk(disk)
          clusterId <- insertK8sCluster(cluster, "RUNNING")
          _ <- insertNodepool(clusterId, "default-np", true)
          nodepoolId <- insertNodepool(clusterId, "np", false)
          namespaceId <- insertNamespace(clusterId, NamespaceName("ns"))
          _ <- insertApp(nodepoolId,
                         namespaceId,
                         "app",
                         diskId,
                         "ERROR",
                         createdDateBeyondGracePeriod,
                         destroyedDateWithinGracePeriod
          )
          clustersToRemove <- dbReader.getKubernetesClustersToDelete.compile.toList
        } yield clustersToRemove.map(_.id) shouldBe List(clusterId)
      }
      res.unsafeRunSync()
    }
  }

  it should "detect for removal: Kubernetes cluster in RUNNING status with only a default nodepool and no apps" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster) =>
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

        for {
          clusterId <- insertK8sCluster(cluster, "RUNNING")
          _ <- insertNodepool(clusterId, "default-np", true)
          clustersToRemove <- dbReader.getKubernetesClustersToDelete.compile.toList
        } yield clustersToRemove.map(_.id) shouldBe List(clusterId)
      }
      res.unsafeRunSync()
    }
  }

  it should "NOT detect for removal: Kubernetes cluster in DELETED status" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster, disk: Disk) =>
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

        for {
          diskId <- insertDisk(disk)
          clusterId <- insertK8sCluster(cluster, "DELETED")
          _ <- insertNodepool(clusterId, "default-np", true)
          nodepoolId <- insertNodepool(clusterId, "np", false)
          namespaceId <- insertNamespace(clusterId, NamespaceName("ns"))
          _ <- insertApp(nodepoolId,
                         namespaceId,
                         "app",
                         diskId,
                         "ERROR",
                         createdDateBeyondGracePeriod,
                         destroyedDateBeyondGracePeriod
          )
          clustersToRemove <- dbReader.getKubernetesClustersToDelete.compile.toList
        } yield clustersToRemove shouldBe List.empty
      }
      res.unsafeRunSync()
    }
  }

  it should "NOT detect for removal: Kubernetes cluster in RUNNING status with app in RUNNING status" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster, disk: Disk) =>
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

        for {
          diskId <- insertDisk(disk)
          clusterId <- insertK8sCluster(cluster, "RUNNING")
          _ <- insertNodepool(clusterId, "default-np", true)
          nodepoolId <- insertNodepool(clusterId, "np", false)
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

  it should "NOT detect for removal: Kubernetes cluster in RUNNING status with app in DELETED status WITHIN grace period" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster, disk: Disk) =>
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

        for {
          diskId <- insertDisk(disk)
          clusterId <- insertK8sCluster(cluster, "RUNNING")
          _ <- insertNodepool(clusterId, "default-np", true)
          nodepoolId <- insertNodepool(clusterId, "np", false)
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

  it should "NOT detect for removal: Kubernetes cluster in RUNNING status with app in ERROR status WITHIN grace period" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster, disk: Disk) =>
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

        for {
          diskId <- insertDisk(disk)
          clusterId <- insertK8sCluster(cluster, "RUNNING")
          _ <- insertNodepool(clusterId, "default-np", true)
          nodepoolId <- insertNodepool(clusterId, "np", false)
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

  it should "detect for removal: Kubernetes cluster with nodepools but no apps" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster) =>
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

        for {
          clusterId <- insertK8sCluster(cluster, "RUNNING")
          _ <- insertNodepool(clusterId, "default-np", true)
          _ <- insertNodepool(clusterId, "np", false)

          clustersToRemove <- dbReader.getKubernetesClustersToDelete.compile.toList
        } yield clustersToRemove.map(_.id) shouldBe List(clusterId)
      }
      res.unsafeRunSync()
    }
  }
}
