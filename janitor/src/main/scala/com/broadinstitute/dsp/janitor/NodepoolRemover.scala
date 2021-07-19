package com.broadinstitute.dsp
package janitor

import cats.effect.{Concurrent, Timer}
import cats.syntax.all._
import cats.mtl.Ask
import org.typelevel.log4cats.Logger
import org.broadinstitute.dsde.workbench.model.TraceId
import JsonCodec._

object NodepoolRemover {
  def impl[F[_]: Timer](
    dbReader: DbReader[F],
    deps: LeoPublisherDeps[F]
  )(implicit F: Concurrent[F], timer: Timer[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, Nodepool] =
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
              val msg = DeleteNodepoolMeesage(n.nodepoolId, n.googleProject, Some(ctx))
              deps.publisher.publishOne(msg)
            } else F.unit
        } yield Some(n)
    }
}
