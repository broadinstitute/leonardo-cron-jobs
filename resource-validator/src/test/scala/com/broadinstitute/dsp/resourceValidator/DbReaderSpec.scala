package com.broadinstitute.dsp
package resourceValidator

import java.time.Instant

import com.broadinstitute.dsp.DBTestHelper._
import com.broadinstitute.dsp.Generators._
import doobie.scalatest.IOChecker
import org.broadinstitute.dsde.workbench.google2.GKEModels.KubernetesClusterId
import org.broadinstitute.dsde.workbench.google2.KubernetesSerializableName.NamespaceName
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec

/**
 * Not running these tests in CI yet since we'll need to set up mysql container and Leonardo tables in CI. Punt for now
 * For running these tests locally, you can
 *   * Start leonardo mysql container locally
 *   * Comment out https://github.com/DataBiosphere/leonardo/blob/develop/http/src/test/scala/org/broadinstitute/dsde/workbench/leonardo/db/TestComponent.scala#L82
 *   * Run a database unit test in leonardo
 *   * Run this spec
 */
@DoNotDiscover
class DbReaderSpec extends AnyFlatSpec with CronJobsTestSuite with IOChecker {
  implicit val config = ConfigSpec.config.database
  val transactor = yoloTransactor

  it should "build deletedDisksQuery properly" in {
    check(DbReader.deletedDisksQuery)
  }

  it should "build initBucketsToDeleteQuery properly" in {
    check(DbReader.initBucketsToDeleteQuery)
  }

  it should "build deletedRuntimeQuery properly" in {
    check(DbReader.deletedRuntimeQuery)
  }

  it should "build erroredRuntimeQuery properly" in {
    check(DbReader.erroredRuntimeQuery)
  }

  it should "build stoppedRuntimeQuery properly" in {
    check(DbReader.stoppedRuntimeQuery)
  }

  it should "build kubernetesClustersToDeleteQuery properly" in {
    check(DbReader.kubernetesClustersToDeleteQuery)
  }

  // TODO: Rename this file as 'DbQueryBuilderSpec' and move the checker-functionality-specific tests below to their own Spec(s)
  val now = Instant.now()
  val gracePeriod = 3600 // in seconds

  val createdDateBeyondGracePeriod = now.minusSeconds(gracePeriod + 100)
  val createdDateWithinGracePeriod = now.minusSeconds(gracePeriod - 50)

  val destroyedDateBeyondGracePeriod = now.minusSeconds(gracePeriod + 200)
  val destroyedDateWithinGracePeriod = now.minusSeconds(gracePeriod - 150)

  it should "detect for removal: Kubernetes cluster in RUNNING status with app in DELETED status BEYOND grace period" in {
    forAll { (cluster: KubernetesClusterId, disk: Disk) =>
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
                         destroyedDateBeyondGracePeriod)

          clustersToRemove <- dbReader.getKubernetesClustersToDelete.compile.toList
        } yield clustersToRemove.map(_.id) shouldBe List(clusterId)
      }
      res.unsafeRunSync()
    }
  }

  it should "detect for removal: Kubernetes cluster in RUNNING status with app in ERROR status BEYOND grace period" in {
    forAll { (cluster: KubernetesClusterId, disk: Disk) =>
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
                         destroyedDateWithinGracePeriod)
          clustersToRemove <- dbReader.getKubernetesClustersToDelete.compile.toList
        } yield clustersToRemove.map(_.id) shouldBe List(clusterId)
      }
      res.unsafeRunSync()
    }
  }

  it should "detect for removal: Kubernetes cluster in RUNNING status with only a default nodepool and no apps" in {
    forAll { (cluster: KubernetesClusterId) =>
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

  it should "NOT detect for removal: Kubernetes cluster in DELETED status" in {
    forAll { (cluster: KubernetesClusterId, disk: Disk) =>
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
                         destroyedDateBeyondGracePeriod)
          clustersToRemove <- dbReader.getKubernetesClustersToDelete.compile.toList
        } yield clustersToRemove shouldBe List.empty
      }
      res.unsafeRunSync()
    }
  }

  it should "NOT detect for removal: Kubernetes cluster in RUNNING status with app in RUNNING status" in {
    forAll { (cluster: KubernetesClusterId, disk: Disk) =>
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
                         destroyedDateBeyondGracePeriod)

          clustersToRemove <- dbReader.getKubernetesClustersToDelete.compile.toList
        } yield clustersToRemove shouldBe List.empty
      }
      res.unsafeRunSync()
    }
  }

  it should "NOT detect for removal: Kubernetes cluster in RUNNING status with app in DELETED status WITHIN grace period" in {
    forAll { (cluster: KubernetesClusterId, disk: Disk) =>
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
                         destroyedDateWithinGracePeriod)

          clustersToRemove <- dbReader.getKubernetesClustersToDelete.compile.toList
        } yield clustersToRemove shouldBe List.empty
      }
      res.unsafeRunSync()
    }
  }

  it should "NOT detect for removal: Kubernetes cluster in RUNNING status with app in ERROR status WITHIN grace period" in {
    forAll { (cluster: KubernetesClusterId, disk: Disk) =>
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
                         destroyedDateBeyondGracePeriod)
          clustersToRemove <- dbReader.getKubernetesClustersToDelete.compile.toList
        } yield clustersToRemove shouldBe List.empty
      }
      res.unsafeRunSync()
    }
  }

  // The scenario below is to mimic a cluster with batch-pre-created nodepools, which we don't want to auto-delete
  it should "NOT detect for removal: Kubernetes cluster with nodepools but no apps" in {
    forAll { (cluster: KubernetesClusterId) =>
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

        for {
          clusterId <- insertK8sCluster(cluster, "RUNNING")
          _ <- insertNodepool(clusterId, "default-np", true)
          _ <- insertNodepool(clusterId, "np", false)

          clustersToRemove <- dbReader.getKubernetesClustersToDelete.compile.toList
        } yield clustersToRemove.map(_.id) shouldBe List.empty
      }
      res.unsafeRunSync()
    }
  }
}
