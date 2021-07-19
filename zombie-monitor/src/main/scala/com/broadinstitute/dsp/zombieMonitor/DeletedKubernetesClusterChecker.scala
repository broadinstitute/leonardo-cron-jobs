package com.broadinstitute.dsp
package zombieMonitor

import cats.effect.{Concurrent, Timer}
import cats.mtl.Ask
import fs2.Stream
import org.typelevel.log4cats.Logger
import org.broadinstitute.dsde.workbench.model.TraceId
import cats.syntax.all._

/**
 * Similar to `DeletedDiskChecker`, but this process all non deleted k8s clusters and check if they still exists in google.
 * If not, we update leonardo DB to reflect that they're deleted
 */
object DeletedKubernetesClusterChecker {
  def impl[F[_]: Timer](
    dbReader: DbReader[F],
    deps: KubernetesClusterCheckerDeps[F]
  )(implicit F: Concurrent[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, K8sClusterToScan] =
    new CheckRunner[F, K8sClusterToScan] {
      override def appName: String = zombieMonitor.appName

      override def resourceToScan: Stream[F, K8sClusterToScan] = dbReader.getk8sClustersToDeleteCandidate

      override def configs = CheckRunnerConfigs(s"deleted-kubernetes", false)

      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps

      def checkResource(cluster: K8sClusterToScan, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[K8sClusterToScan]] =
        for {
          clusterOpt <- deps.gkeService.getCluster(cluster.kubernetesClusterId)
          _ <-
            if (isDryRun) F.unit
            else
              clusterOpt match {
                case None    => dbReader.markK8sClusterDeleted(cluster.id)
                case Some(_) => F.unit
              }
        } yield clusterOpt.fold[Option[K8sClusterToScan]](Some(cluster))(_ => none[K8sClusterToScan])
    }
}
