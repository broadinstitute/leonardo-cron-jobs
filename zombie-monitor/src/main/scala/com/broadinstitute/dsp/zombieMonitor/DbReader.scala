package com.broadinstitute.dsp
package zombieMonitor

import cats.effect.Async
import cats.syntax.all._
import com.broadinstitute.dsp.DbReaderImplicits._
import doobie._
import doobie.implicits._
import fs2.Stream

trait DbReader[F[_]] {
  def getDisksToDeleteCandidate: Stream[F, Disk]
  def getGceGalaxyAppsToDeleteCandidate: Stream[F, GceGalaxyApp]
  def getRuntimeCandidate: Stream[F, Runtime]

  def updateDiskStatus(id: Long): F[Unit]
  def markRuntimeDeleted(id: Long): F[Unit]
  def updateRuntimeStatus(id: Long, status: String): F[Unit]
  def markGalaxyAppDeleted(appId: Long): F[Unit]
  def insertClusterError(clusterId: Long, errorCode: Option[Int], errorMessage: String): F[Unit]
  def updateRuntimeDeletedFrom(runtimeId: Long, deletedFrom: String): F[Unit]
}

object DbReader {
  implicit def apply[F[_]](implicit ev: DbReader[F]): DbReader[F] = ev

  val activeDisksQuery =
    sql"""select id, cloudContext, cloudProvider, name, zone, formattedBy from PERSISTENT_DISK where status != "Deleted" and status != "Error";
        """.query[Disk]

  // We only check runtimes that have been created for more than 1 hour because a newly "Creating" runtime may not exist in Google yet
  val activeRuntimeQuery =
    sql"""
         SELECT DISTINCT c1.id, cloudContext, cloudProvider, runtimeName, rt.cloudService, c1.status, rt.zone, rt.region
            FROM CLUSTER AS c1
            INNER JOIN RUNTIME_CONFIG AS rt ON c1.`runtimeConfigId`=rt.id
            WHERE c1.status!="Deleted" AND c1.status!="Error" AND c1.createdDate < now() - INTERVAL 1 HOUR
        """.query[Runtime]

  // Running Galaxy apps, so GceGalaxyAppChecker can verify their GCE VM still exists. The VM name is
  // galaxy-{appName}; the zone comes from the app's data disk.
  val gceGalaxyAppsQuery =
    sql"""select a.id, a.appName, a.cloudContext, pd.zone
          from APP a
          JOIN PERSISTENT_DISK pd ON a.diskId = pd.id
          where a.appType = 'GALAXY'
          AND a.status = 'RUNNING'
          AND a.cloudProvider = 'GCP'
          AND a.cloudContext IS NOT NULL
        """.query[GceGalaxyApp]

  def updateDiskStatusQuery(id: Int) =
    sql"""
           update PERSISTENT_DISK set status = "Deleted", destroyedDate = now() where id = $id
           """.update

  // Marks the app deleted, detaches its disk, and closes its open usage record.
  def markGalaxyAppDeletedQuery(appId: Long): Update0 =
    sql"""
          UPDATE APP
          LEFT JOIN APP_USAGE ON APP.id = APP_USAGE.appId AND APP_USAGE.stopTime = ${DbTransactor.leonardoDummyDate}
          SET APP.status = "DELETED", APP.destroyedDate = now(), APP.diskId = NULL, APP_USAGE.stopTime = APP.dateAccessed
          where APP.id = $appId""".update

  def markRuntimeDeletedQuery(id: Long) =
    sql"""
           update RUNTIME_CONFIG
            INNER JOIN CLUSTER ON RUNTIME_CONFIG.id = CLUSTER.runtimeConfigId
           set CLUSTER.status = "Deleted", CLUSTER.destroyedDate = now(), CLUSTER.deletedFrom = "zombie-cron-job", persistentDiskId = NULL
           where CLUSTER.id = $id
           """.update

  def updateRuntimeStatusQuery(id: Long, status: String) =
    sql"""
           update CLUSTER set status = $status where id = $id
           """.update

  def insertClusterErrorQuery(clusterId: Long, errorCode: Option[Int], errorMessage: String) =
    sql"""
          INSERT INTO CLUSTER_ERROR
         (clusterId, errorCode, errorMessage, timestamp)
         VALUES (${clusterId}, ${errorCode}, ${errorMessage}, now())
           """.update

  def updateRuntimeDeletedFromQuery(runtimeId: Long, deletedFrom: String) =
    sql"""
      update CLUSTER set deletedFrom = $deletedFrom where id = $runtimeId
      """.update

  def impl[F[_]](xa: Transactor[F])(implicit F: Async[F]): DbReader[F] = new DbReader[F] {
    override def getRuntimeCandidate: Stream[F, Runtime] = activeRuntimeQuery.stream.transact(xa)

    override def getDisksToDeleteCandidate: Stream[F, Disk] =
      activeDisksQuery.stream.transact(xa)

    override def getGceGalaxyAppsToDeleteCandidate: Stream[F, GceGalaxyApp] =
      gceGalaxyAppsQuery.stream.transact(xa)

    override def updateDiskStatus(id: Long): F[Unit] =
      updateDiskStatusQuery(id.toInt).run.transact(xa).void

    override def markGalaxyAppDeleted(appId: Long): F[Unit] =
      markGalaxyAppDeletedQuery(appId).run.transact(xa).void

    override def updateRuntimeStatus(id: Long, status: String): F[Unit] =
      updateRuntimeStatusQuery(id, status).run.transact(xa).void

    override def markRuntimeDeleted(id: Long): F[Unit] = markRuntimeDeletedQuery(id).run.transact(xa).void

    override def insertClusterError(clusterId: Long, errorCode: Option[Int], errorMessage: String): F[Unit] =
      insertClusterErrorQuery(clusterId, errorCode, errorMessage).run.transact(xa).void

    override def updateRuntimeDeletedFrom(runtimeId: Long, deletedFrom: String): F[Unit] =
      updateRuntimeDeletedFromQuery(runtimeId, deletedFrom).run.transact(xa).void
  }
}
