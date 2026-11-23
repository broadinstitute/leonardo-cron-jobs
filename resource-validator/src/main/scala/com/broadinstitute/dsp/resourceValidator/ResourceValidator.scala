package com.broadinstitute.dsp
package resourceValidator

import cats.Parallel
import cats.effect.std.Semaphore
import cats.effect._
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

object ResourceValidator {
  val loggingContext = Map(
    "serviceContext" -> ServiceData(Some(getClass.getPackage.getImplementationVersion)).asJson.toString
  )

  def run[F[_]: Async: Parallel](isDryRun: Boolean,
                                 shouldCheckAll: Boolean,
                                 shouldCheckDeletedRuntimes: Boolean,
                                 shouldCheckDeletingRuntimes: Boolean,
                                 shouldCheckErroredRuntimes: Boolean,
                                 shouldCheckStoppedRuntimes: Boolean,
                                 shouldCheckDeletedDisks: Boolean,
                                 shouldCheckInitBuckets: Boolean,
                                 shouldCheckDataprocWorkers: Boolean
  ): Stream[F, Nothing] = {
    implicit val logger =
      StructuredLogger.withContext[F](Slf4jLogger.getLogger[F])(loggingContext)
    implicit val traceId = Ask.const(TraceId(UUID.randomUUID()))

    for {
      config <- Stream.fromEither(Config.appConfig)
      deps <- Stream.resource(initDependencies(config))
      checkRunnerDep = deps.runtimeCheckerDeps.checkRunnerDeps

      deleteRuntimeCheckerProcess =
        if (shouldCheckAll || shouldCheckDeletedRuntimes)
          Stream.eval(DeletedRuntimeChecker.impl(deps.dbReader, deps.runtimeCheckerDeps).run(isDryRun))
        else Stream.empty

      deletingRuntimeCheckerProcess =
        if (shouldCheckAll || shouldCheckDeletingRuntimes)
          Stream.eval(DeletingRuntimeChecker.impl(deps.dbReader, deps.runtimeCheckerDeps).run(isDryRun))
        else Stream.empty

      deleteDiskCheckerProcess =
        if (shouldCheckAll || shouldCheckDeletedDisks)
          Stream.eval(DeletedDiskChecker.impl[F](deps.dbReader, deps.deletedDiskCheckerDeps).run(isDryRun))
        else Stream.empty

      errorRuntimeCheckerProcess =
        if (shouldCheckAll || shouldCheckErroredRuntimes)
          Stream.eval(ErroredRuntimeChecker.iml(deps.dbReader, deps.runtimeCheckerDeps).run(isDryRun))
        else Stream.empty

      stoppedRuntimeCheckerProcess =
        if (shouldCheckAll || shouldCheckStoppedRuntimes)
          Stream.eval(StoppedRuntimeChecker.iml(deps.dbReader, deps.runtimeCheckerDeps).run(isDryRun))
        else Stream.empty

      removeInitBuckets =
        if (shouldCheckAll || shouldCheckInitBuckets)
          Stream.eval(InitBucketChecker.impl(deps.dbReader, checkRunnerDep).run(isDryRun))
        else Stream.empty

      workerProcess =
        if (shouldCheckAll || shouldCheckDataprocWorkers)
          Stream.eval(DataprocWorkerChecker.impl(deps.dbReader, deps.runtimeCheckerDeps).run(isDryRun))
        else Stream.empty

      processes = Stream(
        deleteRuntimeCheckerProcess,
        deletingRuntimeCheckerProcess,
        errorRuntimeCheckerProcess,
        stoppedRuntimeCheckerProcess,
        deleteDiskCheckerProcess,
        removeInitBuckets,
        workerProcess
      ).covary[F]

      _ <- processes.parJoin(7) // Number of checkers in 'processes'
    } yield ()
  }.drain

  private def initDependencies[F[_]: Async: StructuredLogger: Parallel](
    appConfig: AppConfig
  ): Resource[F, ResourcevalidatorServerDeps[F]] =
    for {
      blockerBound <- Resource.eval(Semaphore[F](250))
      metrics <- OpenTelemetryMetrics.resource("leonardo-cron-jobs", appConfig.prometheus.port)
      runtimeCheckerDeps <- RuntimeCheckerDeps.init(appConfig.runtimeCheckerConfig, metrics, blockerBound)
      diskService <- GoogleDiskService.resource(appConfig.pathToCredential.toString, blockerBound)
      xa <- DbTransactor.init(appConfig.database)
    } yield {
      val checkRunnerDeps = runtimeCheckerDeps.checkRunnerDeps
      val diskCheckerDeps = DiskCheckerDeps(checkRunnerDeps, diskService)
      val dbReader = DbReader.impl(xa)
      ResourcevalidatorServerDeps(runtimeCheckerDeps, diskCheckerDeps, dbReader)
    }
}

final case class ResourcevalidatorServerDeps[F[_]](
  runtimeCheckerDeps: RuntimeCheckerDeps[F],
  deletedDiskCheckerDeps: DiskCheckerDeps[F],
  dbReader: DbReader[F]
)
