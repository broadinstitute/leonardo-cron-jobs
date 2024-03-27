package com.broadinstitute.dsp
package janitor

import cats.effect.Concurrent
import cats.mtl.Ask
import cats.syntax.all._
import com.broadinstitute.dsp.JsonCodec._
import fs2.Stream
import org.broadinstitute.dsde.workbench.model.TraceId
import org.typelevel.log4cats.Logger

object NodepoolRemover {
  def impl[F[_]](
    dbReader: DbReader[F],
    deps: LeoPublisherDeps[F]
  )(implicit F: Concurrent[F], logger: Logger[F]): CheckRunner[F, Nodepool] =
    new CheckRunner[F, Nodepool] {
      override def appName: String = janitor.appName
      override def configs = CheckRunnerConfigs(s"remove-kubernetes-nodepools", shouldAlert = false)
      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps
      override def resourceToScan: Stream[F, Nodepool] = {
        val res = for {
          kubernetesClusterToRemoveCandidates <- dbReader.getKubernetesClustersToDelete.compile.toList
          kubernestesClusterToRemoveIds = kubernetesClusterToRemoveCandidates.map(_.id)
          nodepoolToRemoveCandidates <- dbReader.getNodepoolsToDelete
            .filter { n =>
              !kubernestesClusterToRemoveIds.contains(n.kubernetesClusterId)
            }
            .compile
            .toList
        } yield nodepoolToRemoveCandidates
        Stream.evals(res)
      }

      override def checkResource(n: Nodepool, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[Nodepool]] =
        for {
          ctx <- ev.ask
          res <-
            n.cloudContext match {
              case CloudContext.Gcp(value) =>
                val msg = DeleteNodepoolMessage(n.nodepoolId, value, Some(ctx))
                val publish = if (isDryRun) F.unit else deps.publisher.publishOne(msg, Map("leonardo" -> "true"))
                publish.as(n.some)
              case CloudContext.Azure(_) =>
                logger.warn("Azure k8s NodepoolRemover is not supported").as(none)
            }
        } yield res
    }
}
