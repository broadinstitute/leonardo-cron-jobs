package com.broadinstitute.dsp
package zombieMonitor

import cats.Parallel
import cats.effect.std.Semaphore
import cats.effect.{Async, ExitCode, Resource}
import cats.mtl.Ask
import com.broadinstitute.dsp.JsonCodec.serviceDataEncoder
import fs2.Stream
import io.circe.syntax.EncoderOps
import org.broadinstitute.dsde.workbench.google2.{GKEService, GoogleDiskService}
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
                                 shouldCheckDeletedK8sClusters: Boolean,
                                 shouldCheckDeletedOrErroredNodepool: Boolean
  ): Stream[F, Nothing] = {
    implicit val logger =
      StructuredLogger.withContext[F](Slf4jLogger.getLogger[F])(loggingContext)

    implicit val traceId = Ask.const(TraceId(UUID.randomUUID()))

    for {
      config <- Stream.fromEither(Config.appConfig)
      deps <- Stream.resource(initDependencies(config))

      deleteDiskCheckerProcess =
        if (shouldRunAll || shouldCheckDeletedDisks)
          Stream.eval(DeletedDiskChecker.impl(deps.dbReader, deps.diskCheckerDeps).run(isDryRun))
        else Stream.empty
      deleteRuntimeCheckerProcess =
        if (shouldRunAll || shouldCheckDeletedRuntimes)
          Stream.eval(DeletedOrErroredRuntimeChecker.impl(deps.dbReader, deps.runtimeCheckerDeps).run(isDryRun))
        else Stream.empty
      deletek8sClusterCheckerProcess =
        if (shouldRunAll || shouldCheckDeletedK8sClusters)
          Stream.eval(
            DeletedKubernetesClusterChecker.impl(deps.dbReader, deps.kubernetesClusterCheckerDeps).run(isDryRun)
          )
        else Stream.empty
      deleteOrErroredNodepoolCheckerProcess =
        if (shouldRunAll || shouldCheckDeletedOrErroredNodepool)
          Stream.eval(
            DeletedOrErroredNodepoolChecker.impl(deps.dbReader, deps.kubernetesClusterCheckerDeps).run(isDryRun)
          )
        else Stream.empty

      processes = Stream(deleteDiskCheckerProcess,
                         deleteRuntimeCheckerProcess,
                         deletek8sClusterCheckerProcess,
                         deleteOrErroredNodepoolCheckerProcess
      ).covary[F]

      _ <- processes.parJoin(4) // Number of checkers in 'processes'
    } yield ExitCode.Success
  }.drain

  private def initDependencies[F[_]: Async: StructuredLogger: Parallel](
    appConfig: AppConfig
  ): Resource[F, ZombieMonitorDeps[F]] =
    for {
      blockerBound <- Resource.eval(Semaphore[F](250))
      metrics <- OpenTelemetryMetrics.resource(appConfig.pathToCredential, "leonardo-cron-jobs")
      runtimeCheckerDeps <- RuntimeCheckerDeps.init(appConfig.runtimeCheckerConfig, metrics, blockerBound)
      diskService <- GoogleDiskService.resource(appConfig.pathToCredential.toString, blockerBound)
      gkeService <- GKEService.resource(appConfig.pathToCredential, blockerBound)
      xa <- DbTransactor.init(appConfig.database)
    } yield {
      val dbReader = DbReader.impl(xa)
      val checkRunnerDeps = runtimeCheckerDeps.checkRunnerDeps
      val k8sCheckerDeps = KubernetesClusterCheckerDeps(checkRunnerDeps, gkeService)
      ZombieMonitorDeps(DiskCheckerDeps(checkRunnerDeps, diskService), runtimeCheckerDeps, k8sCheckerDeps, dbReader)
    }
}

final case class ZombieMonitorDeps[F[_]](
  diskCheckerDeps: DiskCheckerDeps[F],
  runtimeCheckerDeps: RuntimeCheckerDeps[F],
  kubernetesClusterCheckerDeps: KubernetesClusterCheckerDeps[F],
  dbReader: DbReader[F]
)
