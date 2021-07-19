package com.broadinstitute.dsp
package resourceValidator

import cats.effect.{Concurrent, Timer}
import cats.syntax.all._
import cats.mtl.Ask
import org.typelevel.log4cats.Logger
import org.broadinstitute.dsde.workbench.google2.GKEModels.KubernetesClusterId
import org.broadinstitute.dsde.workbench.model.TraceId

object DeletedOrErroredKubernetesClusterChecker {
  def impl[F[_]: Timer](
    dbReader: DbReader[F],
    deps: KubernetesClusterCheckerDeps[F]
  )(implicit F: Concurrent[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, KubernetesCluster] =
    new CheckRunner[F, KubernetesCluster] {
      override def appName: String = resourceValidator.appName

      override def configs = CheckRunnerConfigs(s"deleted-kubernetes-clusters", shouldAlert = true)

      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps

      override def checkResource(cluster: KubernetesCluster, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[KubernetesCluster]] = checkKubernetesClusterStatus(cluster, isDryRun)

      override def resourceToScan: fs2.Stream[F, KubernetesCluster] = dbReader.getDeletedAndErroredKubernetesClusters

      def checkKubernetesClusterStatus(cluster: KubernetesCluster, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[KubernetesCluster]] =
        for {
          clusterOpt <- deps.gkeService.getCluster(
            KubernetesClusterId(cluster.googleProject, cluster.location, cluster.clusterName)
          )
          _ <- clusterOpt.traverse_ { _ =>
            if (isDryRun) {
              logger.warn(s"${cluster.toString} still exists in Google. It needs to be deleted")
            } else {
              logger.warn(s"${cluster.toString} still exists in Google. Going to delete") >> deps.gkeService
                .deleteCluster(
                  KubernetesClusterId(cluster.googleProject, cluster.location, cluster.clusterName)
                )
                .void
            }
          }
        } yield clusterOpt.fold(none[KubernetesCluster])(_ => Some(cluster))
    }
}
