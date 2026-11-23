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
 * Checks running GCE Galaxy apps in Leo's DB against their GCE VM in GCP.
 *
 * VM naming convention (from Leonardo's GalaxyVmInterpreter.installGalaxyVm): `galaxy-{appName}`
 *
 * If the VM is found: the Galaxy app is still alive — do nothing.
 * If the VM is not found: the VM was deleted outside of Leo — mark the app as DELETED so Leo's
 * DB reflects reality and the app is removed from the Terra UI.
 */
object GceGalaxyAppChecker {
  def impl[F[_]](
    dbReader: DbReader[F],
    deps: GceGalaxyAppCheckerDeps[F]
  )(implicit F: Concurrent[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, GceGalaxyApp] =
    new CheckRunner[F, GceGalaxyApp] {
      override def appName: String = zombieMonitor.appName

      override def resourceToScan: Stream[F, GceGalaxyApp] =
        dbReader.getGceGalaxyAppsToDeleteCandidate

      // Name kept from the cluster-based version so report paths and metrics stay the same.
      override def configs: CheckRunnerConfigs = CheckRunnerConfigs("deleted-gce-galaxy-cluster", false)

      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps

      def checkResource(app: GceGalaxyApp, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[GceGalaxyApp]] = {
        val vmName = InstanceName(s"galaxy-${app.appName}")
        for {
          vmOpt <- deps.computeService.getInstance(app.project, app.zone, vmName)
          result <- vmOpt match {
            case None =>
              logger.info(
                s"GCE VM ${vmName.value} not found in project ${app.project.value} " +
                  s"zone ${app.zone.value}; marking app ${app.appId} as DELETED"
              ) >>
                (if (!isDryRun) dbReader.markGalaxyAppDeleted(app.appId) else F.unit) >>
                F.pure(app.some)
            case Some(_) =>
              // VM exists — Galaxy is still running, nothing to do.
              F.pure(none[GceGalaxyApp])
          }
        } yield result
      }
    }
}
