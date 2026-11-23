package com.broadinstitute.dsp
package zombieMonitor

import cats.Parallel
import cats.effect.std.Semaphore
import cats.effect.{Async, Resource}
import cats.mtl.Ask
import com.broadinstitute.dsp.JsonCodec.serviceDataEncoder
import fs2.Stream
import io.circe.syntax.EncoderOps
import org.broadinstitute.dsde.workbench.google2.GoogleDiskService
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.util.UUID

object ZombieMonitor {
  val loggingContext = Map(
    "serviceContext" -> ServiceData(Some(getClass.getPackage.getImplementationVersion)).asJson.toString
  )

  def run[F[_]: Async: Parallel](isDryRun: Boolean,
                                 shouldRunAll: Boolean,
                                 shouldCheckDeletedRuntimes: Boolean,
                                 shouldCheckDeletedDisks: Boolean,
                                 shouldCheckDeletedGceGalaxyApps: Boolean = true
  ): Stream[F, Nothing] = {
    implicit val logger =
      StructuredLogger.withContext[F](Slf4jLogger.getLogger[F])(loggingContext)

    implicit val traceId = Ask.const(TraceId(UUID.randomUUID()))

    for {
      config <- Stream.fromEither(Config.appConfig)
      deps <- Stream.resource(initDependencies(config))
      _ <-
        if (isDryRun) Stream.eval(logger.info("Running in dry run mode"))
        else Stream.eval(logger.info("Running in non dry run mode"))
      deleteDiskCheckerProcess =
        if (shouldRunAll || shouldCheckDeletedDisks)
          Stream.eval(DeletedDiskChecker.impl(deps.dbReader, deps.diskCheckerDeps).run(isDryRun))
        else Stream.empty
      deleteRuntimeCheckerProcess =
        if (shouldRunAll || shouldCheckDeletedRuntimes)
          Stream.eval(ActiveRuntimeChecker.impl(deps.dbReader, deps.runtimeCheckerDeps).run(isDryRun))
        else Stream.empty
      deleteGceGalaxyAppCheckerProcess =
        if (shouldRunAll || shouldCheckDeletedGceGalaxyApps)
          Stream.eval(
            GceGalaxyAppChecker.impl(deps.dbReader, deps.gceGalaxyAppCheckerDeps).run(isDryRun)
          )
        else Stream.empty

      processes = Stream(
        deleteDiskCheckerProcess,
        deleteRuntimeCheckerProcess,
        deleteGceGalaxyAppCheckerProcess
      ).covary[F]

      _ <- processes.parJoin(3) // Number of checkers in 'processes'
    } yield ()
  }.drain

  private def initDependencies[F[_]: Async: StructuredLogger: Parallel](
    appConfig: AppConfig
  ): Resource[F, ZombieMonitorDeps[F]] =
    for {
      blockerBound <- Resource.eval(Semaphore[F](250))
      metrics <- OpenTelemetryMetrics.resource("leonardo-cron-jobs", appConfig.prometheus.port)
      runtimeCheckerDeps <- RuntimeCheckerDeps.init(appConfig.runtimeCheckerConfig, metrics, blockerBound)
      diskService <- GoogleDiskService.resource(appConfig.pathToCredential.toString, blockerBound)
      xa <- DbTransactor.init(appConfig.database)
    } yield {
      val dbReader = DbReader.impl(xa)
      val checkRunnerDeps = runtimeCheckerDeps.checkRunnerDeps
      // computeService is reused from runtimeCheckerDeps — it already holds credentials that can
      // access GCE VMs in user workspace projects (used for GCE runtime zombie checks).
      val gceGalaxyAppCheckerDeps =
        GceGalaxyAppCheckerDeps(checkRunnerDeps, runtimeCheckerDeps.computeService)
      ZombieMonitorDeps(DiskCheckerDeps(checkRunnerDeps, diskService),
                        runtimeCheckerDeps,
                        gceGalaxyAppCheckerDeps,
                        dbReader
      )
    }
}

final case class ZombieMonitorDeps[F[_]](
  diskCheckerDeps: DiskCheckerDeps[F],
  runtimeCheckerDeps: RuntimeCheckerDeps[F],
  gceGalaxyAppCheckerDeps: GceGalaxyAppCheckerDeps[F],
  dbReader: DbReader[F]
)
