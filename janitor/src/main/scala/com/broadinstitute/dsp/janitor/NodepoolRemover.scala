package com.broadinstitute.dsp
package janitor

import cats.effect.Concurrent
import cats.mtl.Ask
import cats.syntax.all._
import com.broadinstitute.dsp.JsonCodec._
import org.broadinstitute.dsde.workbench.model.TraceId
import org.typelevel.log4cats.Logger

object NodepoolRemover {
  def impl[F[_]](
    dbReader: DbReader[F],
    deps: LeoPublisherDeps[F]
  )(implicit F: Concurrent[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, Nodepool] =
    new CheckRunner[F, Nodepool] {
      override def appName: String = janitor.appName
      override def configs = CheckRunnerConfigs(s"remove-kubernetes-nodepools", shouldAlert = false)
      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps
      override def resourceToScan: fs2.Stream[F, Nodepool] = dbReader.getNodepoolsToDelete

      override def checkResource(n: Nodepool, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[Nodepool]] =
        for {
          ctx <- ev.ask
          res <-
            n.cloudContext match {
              case CloudContext.Gcp(value) =>
                val msg = DeleteNodepoolMeesage(n.nodepoolId, value, Some(ctx))
                val publish = if (isDryRun) F.unit else deps.publisher.publishOne(msg)
                publish.as(n.some)
              case CloudContext.Azure(_) =>
                logger.warn("Azure k8s NodepoolRemover is not supported").as(none)
            }
        } yield res
    }
}
