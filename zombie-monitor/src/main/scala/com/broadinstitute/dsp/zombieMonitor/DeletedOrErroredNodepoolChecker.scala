package com.broadinstitute.dsp
package zombieMonitor

import cats.effect.Concurrent
import cats.mtl.Ask
import cats.syntax.all._
import fs2.Stream
import org.broadinstitute.dsde.workbench.azure.AzureCloudContext
import org.broadinstitute.dsde.workbench.google2.GKEModels.{KubernetesClusterId, NodepoolId}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.typelevel.log4cats.Logger

/**
 * Scans through all active nodepools, and update DB accordingly when necessary
 *
 * - if nodepool doesn't exist in google anymore, mark it as `DELETED`, mark associated APP as `DELETED` and report this nodepool
 * - If nodepool exist in google in ERROR, mark it as `ERROR`, mark associated APP as `ERROR`  and report this nodepool
 * - if nodepool exist in non ERROR state, do nothing and don't report the nodepool
 */
object DeletedOrErroredNodepoolChecker {
  def impl[F[_]](
    dbReader: DbReader[F],
    deps: KubernetesClusterCheckerDeps[F]
  )(implicit F: Concurrent[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, Nodepool] =
    new CheckRunner[F, Nodepool] {
      override def appName: String = zombieMonitor.appName

      override def resourceToScan: Stream[F, Nodepool] = dbReader.getk8sNodepoolsToDeleteCandidate

      // the report file will container all zombied nodepool, and also error-ed nodepool
      override def configs = CheckRunnerConfigs(s"deleted-or-errored-nodepools", false)

      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps

      def checkResource(nodepool: Nodepool, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[Nodepool]] = {
        val isZombie = nodepool.cloudContext match {
          case CloudContext.Gcp(project) =>
            checkGkeNodepool(
              nodepool.nodepoolId,
              NodepoolId(KubernetesClusterId(project, nodepool.location, nodepool.clusterName), nodepool.nodepoolName),
              isDryRun
            )
          case CloudContext.Azure(cloudContext) => checkAksNodepool(nodepool.nodepoolId, cloudContext, isDryRun)
        }
        F.ifF(isZombie)(nodepool.some, none[Nodepool])
      }

      def checkGkeNodepool(id: Long, gkeNodepoolId: NodepoolId, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Boolean] =
        for {
          nodepoolOpt <- deps.gkeService.getNodepool(gkeNodepoolId)
          deleted <-
            nodepoolOpt match {
              case None =>
                logger.info(s"Going to mark GKE nodepool ${id} and apps as DELETED") >>
                  (if (!isDryRun) dbReader.markNodepoolAndAppDeleted(id) else F.unit) >>
                  F.pure(true)
              case Some(nodepool) if nodepool.getStatus == com.google.container.v1.NodePool.Status.ERROR =>
                logger.info(s"Going to mark GKE nodepool ${id} and apps as ERROR") >>
                  (if (!isDryRun) dbReader.updateNodepoolAndAppStatus(id, "ERROR") else F.unit) >>
                  F.pure(true)
              case _ => F.pure(false)
            }
        } yield deleted

      def checkAksNodepool(id: Long, cloudContext: AzureCloudContext, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Boolean] =
        for {
          clusters <- deps.aksService.listClusters(cloudContext)
          deleted <-
            clusters match {
              case Nil =>
                logger.info(s"Going to mark AKS nodepool ${id} and apps as DELETED") >>
                  (if (!isDryRun) dbReader.markNodepoolAndAppDeleted(id) else F.unit) >>
                  F.pure(true)
              case _ => F.pure(false)
            }
        } yield deleted
    }
}
