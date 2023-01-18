package com.broadinstitute.dsp
package janitor

import cats.effect.Concurrent
import cats.mtl.Ask
import cats.implicits._
import org.broadinstitute.dsde.workbench.model.TraceId
import org.typelevel.log4cats.Logger

object StuckAppReporter {
  def impl[F[_]](
    dbReader: DbReader[F],
    deps: LeoPublisherDeps[F]
  )(implicit F: Concurrent[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, AppToReport] =
    new CheckRunner[F, AppToReport] {
      override def appName: String = janitor.appName

      override def configs = CheckRunnerConfigs(s"alert-apps-stuck-in-creating-deleting-status", shouldAlert = false)

      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps

      override def resourceToScan: fs2.Stream[F, AppToReport] = dbReader.getStuckAppToReport

      override def checkResource(a: AppToReport, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[AppToReport]] =
        for {
          log <-
            if (!isDryRun) logger.info("Reporting apps stuck in Creating or Deleting status").as(Some(a))
            else logger.info("Dry run").as(None)
        } yield log
    }
}
