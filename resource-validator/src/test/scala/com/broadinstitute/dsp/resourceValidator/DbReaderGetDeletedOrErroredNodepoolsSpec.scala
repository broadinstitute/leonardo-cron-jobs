package com.broadinstitute.dsp
package resourceValidator

import cats.effect.unsafe.implicits.global
import com.broadinstitute.dsp.DBTestHelper.{
  getNodepoolName,
  insertK8sCluster,
  insertNodepool,
  transactorResource,
  yoloTransactor
}
import doobie.scalatest.IOChecker
import com.broadinstitute.dsp.Generators._
import org.broadinstitute.dsde.workbench.google2.GKEModels.{KubernetesClusterId, NodepoolName}
import org.broadinstitute.dsde.workbench.google2.Generators.genKubernetesClusterId
import org.scalatest.flatspec.AnyFlatSpec

class DbReaderGetDeletedOrErroredNodepoolsSpec extends AnyFlatSpec with CronJobsTestSuite with IOChecker {
  implicit val config = ConfigSpec.config.database
  val transactor = yoloTransactor

  it should "detect nodepools that are Deleted or Errored in the Leo DB" taggedAs DbTest in {
    forAll { (cluster: KubernetesClusterId) =>
      val res = transactorResource.use { implicit xa =>
        val dbReader = DbReader.impl(xa)

        val cluster2 =
          cluster.copy(project = cluster.project.copy(value = s"${cluster.project}-2"))

        for {
          clusterId <- insertK8sCluster(cluster)
          cluster2Id <- insertK8sCluster(cluster2)
          nodepool1Id <- insertNodepool(clusterId, "nodepool1", true, "DELETED")
          nodepool2Id <- insertNodepool(cluster2Id, "nodepool2", true, "ERROR")
          nodepool3Id <- insertNodepool(cluster2Id, "nodepool3", true, "ERROR")
          _ <- insertNodepool(clusterId, "nodepool4", true, "RUNNING")
          _ <- insertNodepool(cluster2Id, "nodepool5", true, "PROVISIONING")
          _ <- insertNodepool(clusterId, "nodepool6", true, "PREDELETING")

          nodepool1Name <- getNodepoolName(nodepool1Id)
          nodepool2Name <- getNodepoolName(nodepool2Id)
          nodepool3Name <- getNodepoolName(nodepool3Id)

          clustersToDelete <- dbReader.getDeletedAndErroredNodepools.compile.toList
        } yield clustersToDelete.map(_.nodepoolName) shouldBe List(NodepoolName(nodepool1Name),
                                                                   NodepoolName(nodepool2Name),
                                                                   NodepoolName(nodepool3Name)
        )
      }
      res.unsafeRunSync()
    }
  }
}
