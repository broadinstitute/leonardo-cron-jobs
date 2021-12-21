package com.broadinstitute.dsp
package nuker

import cats.Parallel
import cats.effect.{Async, ExitCode, Resource, Sync}
import cats.mtl.Ask
import fs2.Stream
import org.broadinstitute.dsde.workbench.google2.{GoogleSubscriptionAdmin, GoogleTopicAdmin}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.util.UUID

object Nuker {
  def run[F[_]: Async: Parallel](isDryRun: Boolean,
                                 shouldRunAll: Boolean,
                                 shouldDeletePubsubTopics: Boolean
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

  private def initDependencies[F[_]: Async: StructuredLogger: Parallel](
    appConfig: AppConfig
  ): Resource[F, NukerDeps[F]] =
    for {
      metrics <- OpenTelemetryMetrics.resource(appConfig.pathToCredential, "leonardo-cron-jobs")
      credential <- org.broadinstitute.dsde.workbench.google2.credentialResource[F](appConfig.pathToCredential.toString)
      topicAdminClient <- GoogleTopicAdmin.fromServiceAccountCrendential(credential)
      subscriptionClient <- GoogleSubscriptionAdmin.fromServiceAccountCredential(credential)
    } yield NukerDeps(metrics, topicAdminClient, subscriptionClient)
}

final case class NukerDeps[F[_]](
  metrics: OpenTelemetryMetrics[F],
  topicAdminClient: GoogleTopicAdmin[F],
  subscriptionClient: GoogleSubscriptionAdmin[F]
)
