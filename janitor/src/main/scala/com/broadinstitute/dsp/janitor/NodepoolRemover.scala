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
          _ <-
            if (!isDryRun) {
              n.cloudContext match {
                case CloudContext.Gcp(value) =>
                  val msg = DeleteNodepoolMeesage(n.nodepoolId, value, Some(ctx))
                  deps.publisher.publishOne(msg)
                case CloudContext.Azure(_) =>
                  logger.warn("Azure is not supported yet") // TODO: IA-3623
              }

            } else F.unit
        } yield Some(n)
    }
}
