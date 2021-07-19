package com.broadinstitute.dsp
package janitor

import java.util.UUID
import cats.Parallel
import cats.effect.concurrent.Semaphore
import cats.effect.{Blocker, Concurrent, ConcurrentEffect, ContextShift, ExitCode, Resource, Sync, Timer}
import cats.mtl.Ask
import com.google.pubsub.v1.ProjectTopicName
import fs2.Stream
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.broadinstitute.dsde.workbench.google2.{GooglePublisher, PublisherConfig}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics

object Janitor {
  def run[F[_]: ConcurrentEffect: Parallel](isDryRun: Boolean,
                                            shouldCheckAll: Boolean,
                                            shouldCheckKubernetesClustersToBeRemoved: Boolean,
                                            shouldCheckNodepoolsToBeRemoved: Boolean,
                                            shouldCheckStagingBucketsToBeRemoved: Boolean
  )(implicit
    timer: Timer[F],
    cs: ContextShift[F]
  ): Stream[F, Nothing] = {
    implicit def getLogger[F[_]: Sync] = Slf4jLogger.getLogger[F]
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

      processes = Stream(removeKubernetesClusters, removeNodepools, removeStagingBuckets).covary[F]

      _ <- processes.parJoin(3) // Number of checkers in 'processes'
    } yield ExitCode.Success
  }.drain

  private def initDependencies[F[_]: Concurrent: ContextShift: StructuredLogger: Parallel: Timer](
    appConfig: AppConfig
  ): Resource[F, JanitorDeps[F]] =
    for {
      blocker <- Blocker[F]
      blockerBound <- Resource.eval(Semaphore[F](250))
      metrics <- OpenTelemetryMetrics.resource(appConfig.pathToCredential, "leonardo-cron-jobs", blocker)
      runtimeCheckerDeps <- RuntimeCheckerDeps.init(appConfig.runtimeCheckerConfig, blocker, metrics, blockerBound)
      publisherConfig = PublisherConfig(
        appConfig.pathToCredential.toString,
        ProjectTopicName.of(appConfig.leonardoPubsub.googleProject.value, appConfig.leonardoPubsub.topicName)
      )
      googlePublisher <- GooglePublisher.resource[F](publisherConfig)
      xa <- DbTransactor.init(appConfig.database)
    } yield {
      val checkRunnerDeps = runtimeCheckerDeps.checkRunnerDeps
      val kubernetesClusterToRemoveDeps = LeoPublisherDeps(googlePublisher, checkRunnerDeps)
      val dbReader = DbReader.impl(xa)
      JanitorDeps(runtimeCheckerDeps, kubernetesClusterToRemoveDeps, dbReader, blocker)
    }
}

final case class JanitorDeps[F[_]](
  runtimeCheckerDeps: RuntimeCheckerDeps[F],
  leoPublisherDeps: LeoPublisherDeps[F],
  dbReader: DbReader[F],
  blocker: Blocker
)
