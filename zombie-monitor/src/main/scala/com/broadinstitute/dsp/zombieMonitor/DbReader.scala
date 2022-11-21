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
  def getk8sClustersToDeleteCandidate: Stream[F, K8sClusterToScan]
  def getk8sNodepoolsToDeleteCandidate: Stream[F, NodepoolToScan]
  def getRuntimeCandidate: Stream[F, Runtime]

  def updateDiskStatus(id: Long): F[Unit]
  def markRuntimeDeleted(id: Long): F[Unit]
  def updateRuntimeStatus(id: Long, status: String): F[Unit]
  def markK8sClusterDeleted(id: Long): F[Unit]
  def markNodepoolAndAppDeleted(nodepoolId: Long): F[Unit]
  def updateNodepoolAndAppStatus(nodepoolId: Long, status: String): F[Unit]
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

  val activeK8sClustersQuery =
    sql"""select id, clusterName, cloudContext, location, cloudProvider from KUBERNETES_CLUSTER where status != "DELETED" and status != "ERROR";
        """.query[K8sClusterToScan]

  val activeNodepoolsQuery =
    sql"""select np.id, cluster.cloudProvider, cluster.cloudContext, cluster.location, cluster.clusterName, np.nodepoolName from
         	NODEPOOL AS np INNER JOIN KUBERNETES_CLUSTER AS cluster
         	on cluster.id = np.clusterId
         	where np.status != "DELETED" and np.status != "ERROR"
         	""".query[Option[NodepoolToScan]]

  def updateDiskStatusQuery(id: Int) =
    sql"""
           update PERSISTENT_DISK set status = "Deleted", destroyedDate = now() where id = $id
           """.update

  def markK8sClusterDeletedQuery(id: Int) =
    sql"""
          UPDATE KUBERNETES_CLUSTER
          LEFT JOIN NODEPOOL ON KUBERNETES_CLUSTER.id = NODEPOOL.clusterId
          LEFT JOIN APP ON APP.nodepoolId = NODEPOOL.id
          SET diskId = NULL, KUBERNETES_CLUSTER.status = "DELETED", KUBERNETES_CLUSTER.destroyedDate = now(), NODEPOOL.status = "DELETED", NODEPOOL.destroyedDate=now()
          where KUBERNETES_CLUSTER.id = $id""".update

  def markNodepoolDeletedQuery(id: Long) =
    sql"""
           update NODEPOOL set status = "DELETED", destroyedDate = now() where id = $id
           """.update

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

  def updateNodepoolStatus(id: Long, status: String) =
    sql"""
           update NODEPOOL set status = $status where id = $id
           """.update

  def markAppDeletedForNodepoolIdQuery(nodepoolId: Long) =
    sql"""
           update APP set status = "DELETED", destroyedDate = now() where nodepoolId = $nodepoolId
           """.update

  def updateAppStatusForNodepoolId(nodepoolId: Long, status: String) =
    sql"""
           update APP set status = $status where nodepoolId = $nodepoolId
           """.update

  def insertClusterErrorQuery(clusterId: Long, errorCode: Option[Int], errorMessage: String) =
    sql"""
          INSERT INTO CLUSTER_ERROR
         (clusterId, errorCode, errorMessage)
         VALUES (${clusterId}, ${errorCode}, ${errorMessage})
           """.update

  def updateRuntimeDeletedFromQuery(runtimeId: Long, deletedFrom: String) =
    sql"""
      update CLUSTER set deletedFrom = $deletedFrom where id = $runtimeId
      """.update

  def impl[F[_]](xa: Transactor[F])(implicit F: Async[F]): DbReader[F] = new DbReader[F] {
    override def getRuntimeCandidate: Stream[F, Runtime] = activeRuntimeQuery.stream.transact(xa)

    override def getDisksToDeleteCandidate: Stream[F, Disk] =
      activeDisksQuery.stream.transact(xa)

    override def getk8sClustersToDeleteCandidate: Stream[F, K8sClusterToScan] =
      activeK8sClustersQuery.stream.transact(xa)

    override def updateDiskStatus(id: Long): F[Unit] =
      updateDiskStatusQuery(id.toInt).run.transact(xa).void

    override def markK8sClusterDeleted(id: Long): F[Unit] =
      markK8sClusterDeletedQuery(id.toInt).run.transact(xa).void

    override def getk8sNodepoolsToDeleteCandidate: Stream[F, NodepoolToScan] =
      activeNodepoolsQuery.stream.unNone.transact(xa)

    override def markNodepoolAndAppDeleted(nodepoolId: Long): F[Unit] = {
      val res = for {
        _ <- markNodepoolDeletedQuery(nodepoolId).run
        _ <- markAppDeletedForNodepoolIdQuery(nodepoolId).run
      } yield ()
      res.transact(xa)
    }

    override def updateNodepoolAndAppStatus(nodepoolId: Long, status: String): F[Unit] = {
      val res = for {
        _ <- updateNodepoolStatus(nodepoolId, status).run
        _ <- updateAppStatusForNodepoolId(nodepoolId, status).run
      } yield ()
      res.transact(xa)
    }

    override def updateRuntimeStatus(id: Long, status: String): F[Unit] =
      updateRuntimeStatusQuery(id, status).run.transact(xa).void

    override def markRuntimeDeleted(id: Long): F[Unit] = markRuntimeDeletedQuery(id).run.transact(xa).void

    override def insertClusterError(clusterId: Long, errorCode: Option[Int], errorMessage: String): F[Unit] =
      insertClusterErrorQuery(clusterId, errorCode, errorMessage).run.transact(xa).void

    override def updateRuntimeDeletedFrom(runtimeId: Long, deletedFrom: String): F[Unit] =
      updateRuntimeDeletedFromQuery(runtimeId, deletedFrom).run.transact(xa).void
  }
}
