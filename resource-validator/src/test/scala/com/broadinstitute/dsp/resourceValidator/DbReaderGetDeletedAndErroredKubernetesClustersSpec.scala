package com.broadinstitute.dsp
package resourceValidator

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.broadinstitute.dsp.DbTestHelper.{insertK8sCluster, _}
import com.broadinstitute.dsp.Generators._
import doobie.Transactor
import doobie.scalatest.IOChecker
import org.broadinstitute.dsde.workbench.google2.GKEModels.KubernetesClusterName
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.scalatest.flatspec.AnyFlatSpec

class DbReaderGetDeletedAndErroredKubernetesClustersSpec extends AnyFlatSpec with CronJobsTestSuite with IOChecker {
  implicit val config: DatabaseConfig = ConfigSpec.config.database
  implicit val transactor: Transactor[IO] = yoloTransactor

  it should "detect kubernetes clusters that are Deleted or Errored in the Leo DB" taggedAs DbTest in {
    forAll { (cluster: KubernetesCluster) =>
      val res = isolatedDbTest.use { _ =>
        val dbReader = DbReader.impl(transactor)

        val cluster2 =
          cluster.copy(cloudContext = CloudContext.Gcp(GoogleProject("project2")))
        val cluster3 =
          cluster.copy(cloudContext = CloudContext.Gcp(GoogleProject("project3")))
        val cluster4 =
          cluster.copy(cloudContext = CloudContext.Gcp(GoogleProject("project4")))
        val cluster5 =
          cluster.copy(cloudContext = CloudContext.Gcp(GoogleProject("project5")))
        val cluster6 =
          cluster.copy(cloudContext = CloudContext.Gcp(GoogleProject("project6")))

        for {
          cluster1Id <- insertK8sCluster(cluster, "DELETED")
          cluster2Id <- insertK8sCluster(cluster2, "DELETED")
          cluster3Id <- insertK8sCluster(cluster3, "ERROR")
          _ <- insertK8sCluster(cluster4, "RUNNING")
          _ <- insertK8sCluster(cluster5, "PROVISIONING")
          _ <- insertK8sCluster(cluster6, "PRE-CREATING")

          cluster1Name <- getK8sClusterName(cluster1Id)
          cluster2Name <- getK8sClusterName(cluster2Id)
          cluster3Name <- getK8sClusterName(cluster3Id)

          clustersToDelete <- dbReader.getDeletedAndErroredKubernetesClusters.compile.toList
        } yield clustersToDelete.map(_.clusterName) shouldBe List(KubernetesClusterName(cluster1Name),
                                                                  KubernetesClusterName(cluster2Name),
                                                                  KubernetesClusterName(cluster3Name)
        )
      }
      res.unsafeRunSync()
    }
  }
}
