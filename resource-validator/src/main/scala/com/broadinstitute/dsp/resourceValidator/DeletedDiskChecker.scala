package com.broadinstitute.dsp
package resourceValidator

import cats.Parallel
import cats.effect.Concurrent
import cats.mtl.Ask
import cats.syntax.all._
import kotlin.NotImplementedError
import org.broadinstitute.dsde.workbench.google2.DiskName
import org.broadinstitute.dsde.workbench.model.TraceId
import org.typelevel.log4cats.Logger

// Implements CheckRunner[F[_], A]
object DeletedDiskChecker {
  def impl[F[_]: Parallel](
    dbReader: DbReader[F],
    deps: DiskCheckerDeps[F]
  )(implicit F: Concurrent[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, Disk] =
    new CheckRunner[F, Disk] {
      override def appName: String = resourceValidator.appName
      override def configs = CheckRunnerConfigs(s"deleted-disks", true)
      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps
      override def resourceToScan: fs2.Stream[F, Disk] = dbReader.getDeletedDisks

      override def checkResource(disk: Disk, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[Disk]] =
        for {
          googleProject <- disk.cloudContext match {
            case CloudContext.Azure(_) => F.raiseError(new NotImplementedError())
            case CloudContext.Gcp(p) => F.pure(p)
          }
          diskOpt <- deps.googleDiskService.getDisk(googleProject, disk.zone, disk.diskName)
          _ <-
            if (!isDryRun) {
              if (disk.formattedBy.getOrElse(None) == "GALAXY") {
                val dataDiskName = disk.diskName
                val postgresDiskName = DiskName(s"${dataDiskName.value}-gxy-postres-disk")
                diskOpt.traverse { _ =>
                  List(postgresDiskName, dataDiskName).parTraverse(dn =>
                    deps.googleDiskService.deleteDisk(googleProject, disk.zone, dn)
                  )
                }
              } else
                diskOpt.traverse(_ => deps.googleDiskService.deleteDisk(googleProject, disk.zone, disk.diskName))
            } else F.pure(None)
        } yield diskOpt.map(_ => disk)
    }
}
