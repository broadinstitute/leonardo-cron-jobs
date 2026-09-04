package com.broadinstitute.dsp
package zombieMonitor

import cats.effect.Concurrent
import cats.mtl.Ask
import cats.syntax.all._
import fs2.Stream
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.util2.InstanceName
import org.typelevel.log4cats.Logger

/**
 * Checks GCE Galaxy cluster records in Leo's DB against the actual GCE VM in GCP.
 *
 * Galaxy on GCE is stored in KUBERNETES_CLUSTER as a pure DB abstraction — no real GKE cluster is
 * ever created. DeletedKubernetesClusterChecker excludes these rows (they would always look like
 * zombies to the GKE API). This checker handles them correctly by looking up the GCE VM instead.
 *
 * VM naming convention (from GKEInterpreter.installGalaxyVm): `galaxy-{appName}`
 *
 * If the VM is found: the Galaxy app is still alive — do nothing.
 * If the VM is not found: the VM was deleted outside of Leo — mark the cluster as DELETED so Leo's
 * DB reflects reality and the app is removed from the Terra UI.
 */
object GceGalaxyClusterChecker {
  def impl[F[_]](
    dbReader: DbReader[F],
    deps: KubernetesClusterCheckerDeps[F]
  )(implicit F: Concurrent[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, GceGalaxyCluster] =
    new CheckRunner[F, GceGalaxyCluster] {
      override def appName: String = zombieMonitor.appName

      override def resourceToScan: Stream[F, GceGalaxyCluster] =
        dbReader.getGceGalaxyClustersToDeleteCandidate

      override def configs: CheckRunnerConfigs = CheckRunnerConfigs("deleted-gce-galaxy-cluster", false)

      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps

      def checkResource(cluster: GceGalaxyCluster, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[GceGalaxyCluster]] = {
        val vmName = InstanceName(s"galaxy-${cluster.appName}")
        for {
          vmOpt <- deps.computeService.getInstance(cluster.project, cluster.zone, vmName)
          result <- vmOpt match {
            case None =>
              logger.info(
                s"GCE VM ${vmName.value} not found in project ${cluster.project.value} " +
                  s"zone ${cluster.zone.value}; marking cluster ${cluster.clusterId} as DELETED"
              ) >>
                (if (!isDryRun) dbReader.markK8sClusterDeleted(cluster.clusterId) else F.unit) >>
                F.pure(cluster.some)
            case Some(_) =>
              // VM exists — Galaxy is still running, nothing to do.
              F.pure(none[GceGalaxyCluster])
          }
        } yield result
      }
    }
}
