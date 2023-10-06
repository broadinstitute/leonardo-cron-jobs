package com.broadinstitute.dsp
package zombieMonitor

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.broadinstitute.dsp.DbTestHelper._
import com.broadinstitute.dsp.Generators._
import doobie.Transactor
import doobie.implicits._
import doobie.scalatest.IOChecker
import org.broadinstitute.dsde.workbench.azure.{AzureCloudContext, ManagedResourceGroupName, SubscriptionId, TenantId}
import org.broadinstitute.dsde.workbench.google2.DiskName
import org.broadinstitute.dsde.workbench.google2.GKEModels.KubernetesClusterName
import org.broadinstitute.dsde.workbench.google2.KubernetesSerializableName.NamespaceName
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.scalatest.flatspec.AnyFlatSpec

import java.util.{Calendar, GregorianCalendar}

/**
 * Not running these tests in CI yet since we'll need to set up mysql container and Leonardo tables in CI. Punt for now
 * For running these tests locally, you can
 *   - Start leonardo mysql container locally
 *   - Run a Leonardo database unit test (e.g. ClusterComponentSpec)
 *   - Run this spec
 */
final class DbReaderSpec extends AnyFlatSpec with CronJobsTestSuite with IOChecker {
  implicit val databaseConfig: DatabaseConfig = ConfigSpec.config.database
  implicit val transactor: Transactor[IO] = yoloTransactor

  it should "build activeDisksQuery properly" taggedAs DbTest in {
    check(DbReader.activeDisksQuery)
  }

  it should "build activeK8sClustersQuery properly" taggedAs DbTest in {
    check(DbReader.activeK8sClustersQuery)
  }

  it should "build activeNodepoolsQuery properly" taggedAs DbTest in {
    check(DbReader.activeNodepoolsQuery)
  }

  it should "build activeRuntime properly" taggedAs DbTest in {
    check(DbReader.activeRuntimeQuery)
  }

  // This test will fail with `Parameter metadata not available for the given statement`
  // This works fine for real, but doesn't work `check` due to limited support for metadata from mysql
  it should "builds updateDiskStatusQuery properly" ignore {
    check(DbReader.updateDiskStatusQuery(82))
  }

  it should "return active runtimes that are older than an hour" taggedAs DbTest in {
    forAll { (rt: Runtime) =>
      val runtime = Runtime.setStatus(rt, "Running")
      val res = transactorResource.use { _ =>
        val dbReader = DbReader.impl(transactor)

        val oldTimeStamp = new GregorianCalendar(2020, Calendar.OCTOBER, 1).getTime().toInstant
        for {
          runtimeConfigId <- insertRuntimeConfig(runtime.cloudService)
          id <- insertRuntime(runtime, runtimeConfigId, oldTimeStamp)
          runtimes <- dbReader.getRuntimeCandidate.compile.toList
        } yield runtimes should contain theSameElementsAs List(Runtime.setId(runtime, id))
      }
      res.unsafeRunSync()
    }
  }

  it should "not return runtimes that were created within the past hour" taggedAs DbTest in {
    forAll { (rt: Runtime) =>
      val runtime = Runtime.setStatus(rt, "Running")
      val res = transactorResource.use { _ =>
        val dbReader = DbReader.impl(transactor)
        for {
          now <- IO.realTimeInstant
          runtimeConfigId <- insertRuntimeConfig(runtime.cloudService)
          _ <- insertRuntime(runtime, runtimeConfigId, now)
          runtimes <- dbReader.getRuntimeCandidate.compile.toList
        } yield runtimes shouldBe List.empty
      }
      res.unsafeRunSync()
    }
  }

  it should "read a disk properly" taggedAs DbTest in {
    forAll { (disk: Disk) =>
      val res = transactorResource.use { _ =>
        val dbReader = DbReader.impl(transactor)

        val creatingDisk = disk.copy(diskName = DiskName("disk2"))
        val readyDisk = disk.copy(diskName = DiskName("disk3"))

        for {
          _ <- insertDisk(disk, "Deleted")
          _ <- insertDisk(creatingDisk, "Creating")
          _ <- insertDisk(readyDisk)
          d <- dbReader.getDisksToDeleteCandidate.compile.toList
        } yield d.map(_.copy(id = 0L)) should contain theSameElementsAs List(creatingDisk, readyDisk).map(
          _.copy(id = 0L)
        )
      }
      res.unsafeRunSync()
    }
  }

  it should "read a KubernetesCluster properly" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster) =>
      val res = transactorResource.use { _ =>
        val dbReader = DbReader.impl(transactor)

        val precreatingCluster =
          cluster.copy(cloudContext = CloudContext.Gcp(GoogleProject("p1")),
                       clusterName = KubernetesClusterName("cluster2")
          )
        val runningCluster =
          cluster.copy(cloudContext = CloudContext.Gcp(GoogleProject("p2")),
                       clusterName = KubernetesClusterName("cluster3")
          )
        val azureCluster = cluster.copy(
          cloudContext = CloudContext.Azure(
            AzureCloudContext(TenantId("tenant"), SubscriptionId("sub"), ManagedResourceGroupName("mrg"))
          ),
          clusterName = KubernetesClusterName("cluster4")
        )

        for {
          _ <- insertK8sCluster(cluster, "DELETED")
          _ <- insertK8sCluster(precreatingCluster, "PRECREATING")
          _ <- insertK8sCluster(runningCluster, "RUNNING")
          _ <- insertK8sCluster(azureCluster, "RUNNING")
          d <- dbReader.getk8sClustersToDeleteCandidate.compile.toList
        } yield d.map(_.clusterName) should contain theSameElementsAs List(precreatingCluster.clusterName,
                                                                           runningCluster.clusterName,
                                                                           azureCluster.clusterName
        )
      }
      res.unsafeRunSync()
    }
  }

  it should "update disk properly" taggedAs DbTest in {
    forAll { (disk: Disk) =>
      val res = transactorResource.use { _ =>
        val dbReader = DbReader.impl(transactor)
        for {
          id <- insertDisk(disk)
          _ <- dbReader.updateDiskStatus(id)
          status <- getDiskStatus(id)
        } yield status shouldBe "Deleted"
      }
      res.unsafeRunSync()
    }
  }

  it should "update k8s cluster and unlink PD properly" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster, disk: Disk) =>
      val res = transactorResource.use { _ =>
        val dbReader = DbReader.impl(transactor)
        for {
          diskId <- insertDisk(disk)
          clusterId <- insertK8sCluster(cluster)
          nodepoolId <- insertNodepool(clusterId, "nodepool1", false)
          namespaceId <- insertNamespace(clusterId, NamespaceName("ns1"))
          appId <- insertApp(nodepoolId, namespaceId, "app1", diskId)
          _ <- insertAppUsage(appId)
          _ <- dbReader.markK8sClusterDeleted(clusterId)
          status <- getK8sClusterStatus(clusterId)
          pdId <- getPdIdFromK8sCluster(appId)
          appUsageStopTime <- getAppUsageStopTime(appId)
          appDateAccessedTime <- getAppDateAccessed(appId)
        } yield {
          status shouldBe "DELETED"
          pdId shouldBe None
          appUsageStopTime shouldBe appDateAccessedTime
        }
      }
      res.unsafeRunSync()
    }
  }

  it should "update k8s cluster when there's no nodepool or app row" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster) =>
      val res = transactorResource.use { _ =>
        val dbReader = DbReader.impl(transactor)
        for {
          clusterId <- insertK8sCluster(cluster)
          _ <- dbReader.markK8sClusterDeleted(clusterId)
          status <- getK8sClusterStatus(clusterId)
        } yield status shouldBe "DELETED"
      }
      res.unsafeRunSync()
    }
  }

  it should "update k8s cluster and nodepool when there's no App record" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster) =>
      val res = transactorResource.use { _ =>
        val dbReader = DbReader.impl(transactor)
        for {
          clusterId <- insertK8sCluster(cluster)
          nodepoolId <- insertNodepool(clusterId, "nodepool1", false)
          _ <- dbReader.markK8sClusterDeleted(clusterId)
          status <- getK8sClusterStatus(clusterId)
          nodepoolStatus <- getNodepoolStatus(nodepoolId)
        } yield {
          status shouldBe "DELETED"
          nodepoolStatus shouldBe "DELETED"
        }
      }
      res.unsafeRunSync()
    }
  }

  it should "update nodepool status properly" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster) =>
      val res = transactorResource.use { _ =>
        for {
          clusterId <- insertK8sCluster(cluster)
          nodepoolId <- insertNodepool(clusterId, "nodepool1", false)
          _ <- DbReader.updateNodepoolStatus(nodepoolId, "ERROR").run.transact(transactor)
          status <- getNodepoolStatus(nodepoolId)
        } yield status shouldBe "ERROR"
      }
      res.unsafeRunSync()
    }
  }

  it should "update App status properly" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster, disk: Disk) =>
      val res = transactorResource.use { _ =>
        for {
          diskId <- insertDisk(disk)
          clusterId <- insertK8sCluster(cluster)
          nodepoolId <- insertNodepool(clusterId, "nodepool1", false)
          namespaceId <- insertNamespace(clusterId, NamespaceName("ns1"))
          appId <- insertApp(nodepoolId, namespaceId, "app1", diskId)
          _ <- DbReader.updateAppStatusForNodepoolId(nodepoolId, "DELETED").run.transact(transactor)
          status <- getAppStatus(appId)
        } yield status shouldBe "DELETED"
      }
      res.unsafeRunSync()
    }
  }

  it should "update nodepool and app status properly" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster, disk: Disk) =>
      val res = transactorResource.use { _ =>
        val dbReader = DbReader.impl(transactor)

        for {
          diskId <- insertDisk(disk)
          clusterId <- insertK8sCluster(cluster)
          nodepoolId <- insertNodepool(clusterId, "nodepool1", false)
          namespaceId <- insertNamespace(clusterId, NamespaceName("ns1"))
          appId <- insertApp(nodepoolId, namespaceId, "app1", diskId)
          _ <- dbReader.updateNodepoolAndAppStatus(nodepoolId, "ERROR")
          appStatus <- getAppStatus(appId)
          nodepoolStatus <- getNodepoolStatus(nodepoolId)
        } yield {
          appStatus shouldBe "ERROR"
          nodepoolStatus shouldBe "ERROR"
        }
      }
      res.unsafeRunSync()
    }
  }

  it should "update DB properly when Nodepool is deleted" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster, disk: Disk) =>
      val res = transactorResource.use { _ =>
        val dbReader = DbReader.impl(transactor)

        for {
          diskId <- insertDisk(disk)
          clusterId <- insertK8sCluster(cluster)
          nodepoolId <- insertNodepool(clusterId, "nodepool1", false)
          namespaceId <- insertNamespace(clusterId, NamespaceName("ns1"))
          appId <- insertApp(nodepoolId, namespaceId, "app1", diskId)
          _ <- insertAppUsage(appId)
          _ <- dbReader.markNodepoolAndAppDeleted(nodepoolId)
          appStatus <- getAppStatus(appId)
          nodepoolStatus <- getNodepoolStatus(nodepoolId)
          appUsageStopTime <- getAppUsageStopTime(appId)
          appDateAccessedTime <- getAppDateAccessed(appId)
        } yield {
          appStatus shouldBe "DELETED"
          nodepoolStatus shouldBe "DELETED"
          appUsageStopTime shouldBe appDateAccessedTime
        }
      }
      res.unsafeRunSync()
    }
  }

  it should "update runtime status and unlink PD properly" taggedAs DbTest in {
    forAll { (runtime: Runtime, cloudService: CloudService) =>
      val res = transactorResource.use { _ =>
        val dbReader = DbReader.impl(transactor)

        for {
          runtimeConfigId <- insertRuntimeConfig(cloudService)
          runtimeId <- insertRuntime(runtime, runtimeConfigId)
          _ <- dbReader.markRuntimeDeleted(runtimeId)
          status <- getRuntimeStatus(runtimeId)
          pdId <- getPdIdFromRuntimeConfig(runtimeConfigId)
        } yield {
          status shouldBe "Deleted"
          pdId shouldBe None
        }
      }
      res.unsafeRunSync()
    }
  }

  it should "update CLUSTER_ERROR table properly" taggedAs DbTest in {
    forAll { (runtime: Runtime, cloudService: CloudService) =>
      val res = transactorResource.use { _ =>
        val dbReader = DbReader.impl(transactor)

        for {
          runtimeConfigId <- insertRuntimeConfig(cloudService)
          runtimeId <- insertRuntime(runtime, runtimeConfigId)
          _ <- dbReader.insertClusterError(runtimeId, Some(1), "cluster error")
          error <- getRuntimeError(runtimeId)
        } yield {
          error.errorCode shouldBe Some(1)
          error.errorMessage shouldBe "cluster error"
        }
      }
      res.unsafeRunSync()
    }
  }

}
