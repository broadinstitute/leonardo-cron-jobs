package com.broadinstitute.dsp
package janitor

import cats.Parallel
import cats.effect.std.Semaphore
import cats.effect.{Async, ExitCode, Resource}
import cats.mtl.Ask
import com.broadinstitute.dsp.JsonCodec.serviceDataEncoder
import com.google.pubsub.v1.ProjectTopicName
import fs2.Stream
import io.circe.syntax.EncoderOps
import org.broadinstitute.dsde.workbench.google2.{GoogleBillingService, GooglePublisher, PublisherConfig}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.util.UUID

object Janitor {
  val loggingContext = Map(
    "serviceContext" -> ServiceData(Some(getClass.getPackage.getImplementationVersion)).asJson.toString
  )
  def run[F[_]: Async: Parallel](isDryRun: Boolean,
                                 shouldCheckAll: Boolean,
                                 shouldCheckKubernetesClustersToBeRemoved: Boolean,
                                 shouldCheckNodepoolsToBeRemoved: Boolean,
                                 shouldCheckStagingBucketsToBeRemoved: Boolean,
                                 shouldCheckStuckAppsToBeReported: Boolean
  ): Stream[F, Nothing] = {
    implicit val logger =
      StructuredLogger.withContext[F](Slf4jLogger.getLogger[F])(loggingContext)
    implicit val traceId = Ask.const(TraceId(UUID.randomUUID()))

    for {
      config <- Stream.fromEither(Config.appConfig)
      deps <- Stream.resource(initDependencies(config))
      checkRunnerDep = deps.runtimeCheckerDeps.checkRunnerDeps

      removeKubernetesClusters =
        if (shouldCheckAll || shouldCheckKubernetesClustersToBeRemoved)
          Stream.eval(KubernetesClusterRemover.impl(deps.dbReader, deps.leoPublisherDeps).run(isDryRun))
        else Stream.empty

      removeNodepools =
        if (shouldCheckAll || shouldCheckNodepoolsToBeRemoved)
          Stream.eval(NodepoolRemover.impl(deps.dbReader, deps.leoPublisherDeps).run(isDryRun))
        else Stream.empty

      removeStagingBuckets =
        if (shouldCheckAll || shouldCheckStagingBucketsToBeRemoved)
          Stream.eval(StagingBucketRemover.impl(deps.dbReader, checkRunnerDep).run(isDryRun))
        else Stream.empty

      reportStuckApps =
        if (shouldCheckAll || shouldCheckStuckAppsToBeReported)
          Stream.eval(StuckAppReporter.impl(deps.dbReader).run(isDryRun))

      processes = Stream(removeKubernetesClusters, removeNodepools, removeStagingBuckets, reportStuckApps).covary[F]

      _ <- processes.parJoin(4) // Number of checkers in 'processes'
    } yield ExitCode.Success
  }.drain

  private def initDependencies[F[_]: Async: StructuredLogger: Parallel](
    appConfig: AppConfig
  ): Resource[F, JanitorDeps[F]] =
    for {
      blockerBound <- Resource.eval(Semaphore[F](250))
      metrics <- OpenTelemetryMetrics.resource("leonardo-cron-jobs", appConfig.prometheus.port)
      runtimeCheckerDeps <- RuntimeCheckerDeps.init(appConfig.runtimeCheckerConfig, metrics, blockerBound)
      publisherConfig = PublisherConfig(
        appConfig.pathToCredential.toString,
        ProjectTopicName.of(appConfig.leonardoPubsub.googleProject.value, appConfig.leonardoPubsub.topicName)
      )
      googlePublisher <- GooglePublisher.resource[F](publisherConfig)
      scopedCredential <- initGoogleCredentials(appConfig.pathToCredential)
      billingService <- GoogleBillingService.fromCredential(scopedCredential, blockerBound)
      xa <- DbTransactor.init(appConfig.database)
    } yield {
      val checkRunnerDeps = runtimeCheckerDeps.checkRunnerDeps
      val kubernetesClusterToRemoveDeps = LeoPublisherDeps(googlePublisher, checkRunnerDeps, billingService)
      val dbReader = DbReader.impl(xa)
      JanitorDeps(runtimeCheckerDeps, kubernetesClusterToRemoveDeps, dbReader)
    }
}

final case class JanitorDeps[F[_]](
  runtimeCheckerDeps: RuntimeCheckerDeps[F],
  leoPublisherDeps: LeoPublisherDeps[F],
  dbReader: DbReader[F]
)
