package com.broadinstitute.dsp
package nuker

import java.util.concurrent.TimeUnit

import cats.effect.{Concurrent, Timer}
import cats.mtl.Ask
import com.google.pubsub.v1.{ProjectSubscriptionName, TopicName}
import fs2.Stream
import org.typelevel.log4cats.Logger
import org.broadinstitute.dsde.workbench.google2.{GoogleSubscriptionAdmin, GoogleTopicAdmin}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics

class PubsubTopicAndSubscriptionCleaner[F[_]: Timer](config: PubsubTopicCleanerConfig,
                                                     topicAdmin: GoogleTopicAdmin[F],
                                                     subcriptionAdmin: GoogleSubscriptionAdmin[F],
                                                     metrics: OpenTelemetryMetrics[F]
)(implicit
  F: Concurrent[F],
  logger: Logger[F]
) {
  def run(isDryRun: Boolean): F[Unit] = {
    val res = for {
      now <- Stream.eval(Timer[F].clock.realTime(TimeUnit.MILLISECONDS))
      traceId = TraceId(s"pubsubTopicCleaner-${now}")
      implicit0(ev: Ask[F, TraceId]) = Ask.const[F, TraceId](traceId)
      deleteTopicStream = for {
        topic <- topicAdmin.list(config.googleProject)
        topicName = TopicName.parse(topic.getName)
        _ <-
          if (
            topicName.getTopic.startsWith("leonardo-pubsub-") || topicName.getTopic.startsWith(
              "hamm-metadata-topic-fiab"
            )
          ) {
            if (isDryRun)
              Stream.eval(logger.info(s"${topicName} will be deleted if run in nonDryRun mode"))
            else
              Stream.eval(metrics.incrementCounter("pubsubTopicAndSubscriptionCleaner")) >> Stream.eval(
                topicAdmin.delete(topicName, Some(traceId))
              )
          } else Stream.eval(F.unit)
      } yield ()
      deleteSubscriptionStream = for {
        subscription <- subcriptionAdmin.list(config.googleProject)
        _ <-
          if (subscription.getTopic == "_deleted-topic_") { // Google marks the associated topic `_deleted-topic_` if it has been deleted
            if (isDryRun)
              Stream.eval(logger.info(s"${subscription.getName} will be deleted if run in nonDryRun mode"))
            else
              Stream.eval(metrics.incrementCounter("pubsubTopicAndSubscriptionCleaner")) >> Stream.eval(
                subcriptionAdmin.delete(
                  ProjectSubscriptionName.parse(subscription.getName)
                )
              )
          } else Stream.eval(F.unit)
      } yield ()
      _ <- Stream.emits(List(deleteTopicStream, deleteSubscriptionStream)).covary[F].parJoinUnbounded
    } yield ()

    res.compile.drain
  }
}

object PubsubTopicAndSubscriptionCleaner {
  def apply[F[_]: Timer](config: PubsubTopicCleanerConfig,
                         topicAdmin: GoogleTopicAdmin[F],
                         subscriptionAdmin: GoogleSubscriptionAdmin[F],
                         metrics: OpenTelemetryMetrics[F]
  )(implicit
    F: Concurrent[F],
    logger: Logger[F]
  ): PubsubTopicAndSubscriptionCleaner[F] =
    new PubsubTopicAndSubscriptionCleaner(config, topicAdmin, subscriptionAdmin, metrics)
}

final case class PubsubTopicCleanerConfig(googleProject: GoogleProject)
