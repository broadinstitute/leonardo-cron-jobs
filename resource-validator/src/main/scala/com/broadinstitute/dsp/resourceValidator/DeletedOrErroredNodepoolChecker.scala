package com.broadinstitute.dsp
package resourceValidator

import cats.effect.Async
import cats.mtl.Ask
import cats.syntax.all._
import com.broadinstitute.dsp.JsonCodec._
import org.broadinstitute.dsde.workbench.google2.GKEModels.{KubernetesClusterId, NodepoolId}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.typelevel.log4cats.Logger

object DeletedOrErroredNodepoolChecker {

  def impl[F[_]](
    dbReader: DbReader[F],
    deps: NodepoolCheckerDeps[F]
  )(implicit F: Async[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, Nodepool] =
    new CheckRunner[F, Nodepool] {
      override def appName: String = resourceValidator.appName

      override def configs = CheckRunnerConfigs(s"deleted-nodepools", shouldAlert = true)

      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps

      override def checkResource(nodepool: Nodepool, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[Nodepool]] = checkNodepoolStatus(nodepool, isDryRun)

      override def resourceToScan: fs2.Stream[F, Nodepool] = dbReader.getDeletedAndErroredNodepools

      def checkNodepoolStatus(nodepool: Nodepool, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[Nodepool]] =
        for {
          now <- F.realTimeInstant
          nodepoolOpt <- deps.gkeService.getNodepool(
            NodepoolId(KubernetesClusterId(nodepool.googleProject, nodepool.location, nodepool.clusterName),
                       nodepool.nodepoolName
            )
          )
          _ <- nodepoolOpt.traverse_ { _ =>
            if (isDryRun) {
              logger.warn(s"${nodepool.toString} still exists in Google. It needs to be deleted")
            } else {
              val msg = DeleteNodepoolMeesage(nodepool.nodepoolId,
                                              nodepool.googleProject,
                                              Some(TraceId(s"DeletedOrErroredNodepoolChecker-$now"))
              )
              logger.warn(s"${nodepool.toString} still exists in Google. Going to delete") >> deps.publisher.publishOne(
                msg
              )
            }
          }
        } yield nodepoolOpt.fold(none[Nodepool])(_ => Some(nodepool))
    }
}
