package com.broadinstitute.dsp
package zombieMonitor

import cats.effect.Concurrent
import cats.mtl.Ask
import cats.syntax.all._
import fs2.Stream
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
  )(implicit F: Concurrent[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, NodepoolToScan] =
    new CheckRunner[F, NodepoolToScan] {
      override def appName: String = zombieMonitor.appName

      override def resourceToScan: Stream[F, NodepoolToScan] = dbReader.getk8sNodepoolsToDeleteCandidate

      // the report file will container all zombied nodepool, and also error-ed nodepool
      override def configs = CheckRunnerConfigs(s"deleted-or-errored-nodepools", false)

      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps

      def checkResource(nodepoolToScan: NodepoolToScan, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[NodepoolToScan]] =
        for {
          nodepoolOpt <- deps.gkeService.getNodepool(nodepoolToScan.nodepoolId)
          nodepoolToReport <- nodepoolOpt match {
            case None =>
              (if (isDryRun) F.unit
               else
                 dbReader.markNodepoolAndAppDeleted(nodepoolToScan.id)).as(Some(nodepoolToScan))
            case Some(nodepool) =>
              if (nodepool.getStatus == com.google.container.v1.NodePool.Status.ERROR) {
                (if (isDryRun) F.unit
                 else
                   dbReader.updateNodepoolAndAppStatus(nodepoolToScan.id, "ERROR")).as(Some(nodepoolToScan))
              } else
                F.pure(none[NodepoolToScan])
          }
        } yield nodepoolToReport
    }
}
