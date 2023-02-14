package com.broadinstitute.dsp
package zombieMonitor

import cats.effect.Concurrent
import cats.mtl.Ask
import cats.syntax.all._
import fs2.Stream
import org.broadinstitute.dsde.workbench.azure.AzureCloudContext
import org.broadinstitute.dsde.workbench.google2.GKEModels.KubernetesClusterId
import org.broadinstitute.dsde.workbench.model.TraceId
import org.typelevel.log4cats.Logger

/**
 * Similar to `DeletedDiskChecker`, but this process all non deleted k8s clusters and check if they still exists in google.
 * If not, we update leonardo DB to reflect that they're deleted
 */
object DeletedKubernetesClusterChecker {
  def impl[F[_]](
    dbReader: DbReader[F],
    deps: KubernetesClusterCheckerDeps[F]
  )(implicit F: Concurrent[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, KubernetesCluster] =
    new CheckRunner[F, KubernetesCluster] {
      override def appName: String = zombieMonitor.appName

      override def resourceToScan: Stream[F, KubernetesCluster] = dbReader.getk8sClustersToDeleteCandidate

      override def configs = CheckRunnerConfigs(s"deleted-kubernetes", false)

      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps

      def checkResource(cluster: KubernetesCluster, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[KubernetesCluster]] = {
        val isZombie = cluster.cloudContext match {
          case CloudContext.Gcp(project) =>
            checkGkeClusterStatus(cluster.id,
                                  KubernetesClusterId(project, cluster.location, cluster.clusterName),
                                  isDryRun
            )
          case CloudContext.Azure(cloudContext) => checkAksClusterStatus(cluster.id, cloudContext, isDryRun)
        }
        F.ifF(isZombie)(cluster.some, none[KubernetesCluster])
      }

      def checkGkeClusterStatus(id: Long, gkeClusterId: KubernetesClusterId, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Boolean] =
        for {
          clusterOpt <- deps.gkeService.getCluster(gkeClusterId)
          deleted <-
            if (isDryRun) F.pure(false)
            else
              clusterOpt match {
                case None =>
                  logger.info(s"Going to mark GKE cluster ${id} as DELETED") >> dbReader.markK8sClusterDeleted(id) >> F
                    .pure(true)
                case Some(_) => F.pure(false)
              }
        } yield deleted

      def checkAksClusterStatus(id: Long, cloudContext: AzureCloudContext, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Boolean] =
        for {
          clusters <- deps.aksService.listClusters(cloudContext)
          deleted <-
            if (isDryRun) F.pure(false)
            else
              clusters match {
                case Nil =>
                  logger.info(s"Going to mark AKS cluster ${id} as DELETED") >> dbReader.markK8sClusterDeleted(id) >> F
                    .pure(true)
                case _ => F.pure(false)
              }
        } yield deleted
    }
}
