package com.broadinstitute.dsp
package nuker

import java.util.UUID

import cats.Parallel
import cats.effect.{Blocker, Concurrent, ConcurrentEffect, ContextShift, ExitCode, Resource, Sync, Timer}
import cats.mtl.Ask
import fs2.Stream
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.broadinstitute.dsde.workbench.google2.{GoogleSubscriptionAdmin, GoogleTopicAdmin}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics

object Nuker {
  def run[F[_]: ConcurrentEffect: Parallel](isDryRun: Boolean,
                                            shouldRunAll: Boolean,
                                            shouldDeletePubsubTopics: Boolean
  )(implicit
    timer: Timer[F],
    cs: ContextShift[F]
  ): Stream[F, Nothing] = {
    implicit def getLogger[F[_]: Sync] = Slf4jLogger.getLogger[F]
    implicit val traceId = Ask.const(TraceId(UUID.randomUUID()))

    for {
      config <- Stream.fromEither(Config.appConfig)
      deps <- Stream.resource(initDependencies(config))
      deleteRuntimeCheckerProcess =
        if (shouldRunAll || shouldDeletePubsubTopics)
          Stream.eval(
            PubsubTopicAndSubscriptionCleaner(config.pubsubTopicCleaner,
                                              deps.topicAdminClient,
                                              deps.subscriptionClient,
                                              deps.metrics
            )
              .run(isDryRun)
          )
        else Stream.empty

      processes = Stream(deleteRuntimeCheckerProcess).covary[F]
      _ <- processes.parJoin(2)
    } yield ExitCode.Success
  }.drain

  private def initDependencies[F[_]: Concurrent: ContextShift: StructuredLogger: Parallel: Timer](
    appConfig: AppConfig
  ): Resource[F, NukerDeps[F]] =
    for {
      blocker <- Blocker[F]
      metrics <- OpenTelemetryMetrics.resource(appConfig.pathToCredential, "leonardo-cron-jobs", blocker)
      credential <- org.broadinstitute.dsde.workbench.google2.credentialResource[F](appConfig.pathToCredential.toString)
      topicAdminClient <- GoogleTopicAdmin.fromServiceAccountCrendential(credential)
      subscriptionClient <- GoogleSubscriptionAdmin.fromServiceAccountCredential(credential)
    } yield NukerDeps(blocker, metrics, topicAdminClient, subscriptionClient)
}

final case class NukerDeps[F[_]](
  blocker: Blocker,
  metrics: OpenTelemetryMetrics[F],
  topicAdminClient: GoogleTopicAdmin[F],
  subscriptionClient: GoogleSubscriptionAdmin[F]
)
