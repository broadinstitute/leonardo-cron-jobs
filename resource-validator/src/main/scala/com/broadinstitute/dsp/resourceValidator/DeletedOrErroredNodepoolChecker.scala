package com.broadinstitute.dsp
package resourceValidator

import java.util.concurrent.TimeUnit

import cats.effect.{Concurrent, Timer}
import cats.syntax.all._
import cats.mtl.Ask
import org.typelevel.log4cats.Logger
import org.broadinstitute.dsde.workbench.google2.GKEModels.{KubernetesClusterId, NodepoolId}
import org.broadinstitute.dsde.workbench.model.TraceId

import JsonCodec._

object DeletedOrErroredNodepoolChecker {

  def impl[F[_]: Timer](
    dbReader: DbReader[F],
    deps: NodepoolCheckerDeps[F]
  )(implicit F: Concurrent[F], timer: Timer[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, Nodepool] =
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
          now <- timer.clock.realTime(TimeUnit.MILLISECONDS)
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
