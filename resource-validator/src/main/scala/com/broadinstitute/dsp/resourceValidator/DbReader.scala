package com.broadinstitute.dsp
package resourceValidator

import cats.effect.Async
import com.broadinstitute.dsp.DbReaderImplicits._
import com.broadinstitute.dsp.DbTransactor.leonardoDummyDate
import doobie._
import doobie.implicits._
import fs2.Stream
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GoogleProject}

trait DbReader[F[_]] {
  def getDeletedDisks: Stream[F, Disk]
  def getDeletedRuntimes: Stream[F, Runtime]
  def getDeletingRuntimes: Stream[F, Runtime]
  def getErroredRuntimes: Stream[F, Runtime]
  def getStoppedRuntimes: Stream[F, Runtime]
  def getInitBucketsToDelete: Stream[F, InitBucketToRemove]
  def getDeletedAndErroredKubernetesClusters: Stream[F, KubernetesCluster]
  def getDeletedAndErroredNodepools: Stream[F, Nodepool]
  def getRuntimesWithWorkers: Stream[F, RuntimeWithWorkers]
}

object DbReader {
  implicit def apply[F[_]](implicit ev: DbReader[F]): DbReader[F] = ev

  val shouldBedeletedDisksQuery =
    sql"""
           SELECT pd1.id, pd1.cloudContext, pd1.cloudProvider, pd1.name, pd1.zone, pd1.formattedBy
           FROM PERSISTENT_DISK AS pd1
           WHERE
           (
             (pd1.status="Deleted" AND pd1.destroyedDate > now() - INTERVAL 30 DAY) OR
             (pd1.status="Deleting" AND pd1.`dateAccessed` < now() - INTERVAL 30 MINUTE) OR
             (pd1.status="Deleted" AND pd1.destroyedDate == '${leonardoDummyDate}')
           ) AND
             NOT EXISTS
             (
               SELECT *
               FROM PERSISTENT_DISK pd2
               WHERE pd1.cloudContext = pd2.cloudContext and pd1.name = pd2.name and pd2.status != "Deleted" and pd2.status != "Deleting"
              )
        """.query[Disk]

  val initBucketsToDeleteQuery =
    sql"""SELECT cloudContext, initBucket FROM CLUSTER WHERE status="Deleted";"""
      .query[InitBucketToRemove]

  val deletedRuntimeQuery =
    sql"""SELECT DISTINCT c1.id, cloudContext, c1.cloudProvider, runtimeName, rt.cloudService, c1.status, rt.zone, rt.region
          FROM CLUSTER AS c1
          INNER JOIN RUNTIME_CONFIG AS rt ON c1.runtimeConfigId = rt.id
          WHERE
            c1.status = "Deleted" AND
            c1.destroyedDate > now() - INTERVAL 30 DAY AND
            NOT EXISTS (
              SELECT *
              FROM CLUSTER AS c2
              WHERE
                c2.cloudContext = c1.cloudContext AND
                c2.runtimeName = c1.runtimeName AND
                (c2.status = "Stopped" OR c2.status = "Running")
          )"""
      .query[Runtime]

  val deletingRuntimeQuery =
    sql"""SELECT DISTINCT c1.id, cloudContext, c1.cloudProvider, runtimeName, rt.cloudService, c1.status, rt.zone, rt.region
          FROM CLUSTER AS c1
          INNER JOIN RUNTIME_CONFIG AS rt ON c1.runtimeConfigId = rt.id
          WHERE
            c1.status = "Deleting" AND
            c1.dateAccessed < now() - INTERVAL 1 HOUR AND
            NOT EXISTS (
              SELECT *
              FROM CLUSTER AS c2
              WHERE
                c2.cloudContext = c1.cloudContext AND
                c2.runtimeName = c1.runtimeName AND
                (c2.status = "Stopped" OR c2.status = "Running")
          )"""
      .query[Runtime]

  val erroredRuntimeQuery =
    sql"""SELECT DISTINCT c1.id, cloudContext, cloudProvider, runtimeName, rt.cloudService, c1.status, rt.zone, rt.region
          FROM CLUSTER AS c1
          INNER JOIN RUNTIME_CONFIG AS rt ON c1.runtimeConfigId = rt.id
          WHERE
           c1.status = "Error" AND
           NOT EXISTS (
              SELECT *
              FROM CLUSTER AS c2
              WHERE
                c2.cloudContext = c1.cloudContext AND
                c2.runtimeName=c1.runtimeName AND
                (c2.status = "Stopped" OR c2.status = "Running")
             )"""
      .query[Runtime]

  val stoppedRuntimeQuery =
    sql"""SELECT DISTINCT c1.id, c1.cloudContext, c1.cloudProvider, c1.runtimeName, rt.cloudService, c1.status, rt.zone, rt.region
          FROM CLUSTER AS c1
          INNER JOIN RUNTIME_CONFIG AS rt ON c1.runtimeConfigId = rt.id
          WHERE
            c1.status = "Stopped" AND
            NOT EXISTS (
              SELECT *
              FROM CLUSTER AS c2
              WHERE
                c2.cloudContext = c1.cloudContext AND
                c2.runtimeName = c1.runtimeName AND
                c2.status = "Running"
             )"""
      .query[Runtime]

  // Leonardo doesn't manage cluster for Azure. Hence excluding AKS clusters
  val deletedAndErroredKubernetesClusterQuery =
    sql"""SELECT kc1.id, kc1.clusterName, cloudContext, location, cloudProvider
          FROM KUBERNETES_CLUSTER as kc1
          WHERE (kc1.status="DELETED" OR kc1.status="ERROR") AND kc1.cloudProvider = "GCP"
          """
      .query[KubernetesCluster]

  // Leonardo doesn't manage nodepool for Azure. Hence excluding Azure nodepools
  val deletedAndErroredNodepoolQuery =
    sql"""SELECT np.id, np.nodepoolName, kc.id, kc.clusterName, kc.cloudProvider, kc.cloudContext, kc.location
         FROM NODEPOOL AS np
         INNER JOIN KUBERNETES_CLUSTER AS kc ON np.clusterId = kc.id
         WHERE (np.status="DELETED" OR np.status="ERROR") AND kc.cloudProvider = "GCP"
         """
      .query[Nodepool]

  // We're excluding cluster id 6220 because it's a known anomaly and user ed team has reached out to hufengzhou@g.harvard.edu
  val dataprocClusterWithWorkersQuery =
    sql"""SELECT DISTINCT c1.id, cloudContext, runtimeName, rt.cloudService, c1.status, rt.region, rt.numberOfWorkers, rt.numberOfPreemptibleWorkers
          FROM CLUSTER AS c1
          INNER JOIN RUNTIME_CONFIG AS rt ON c1.`runtimeConfigId`=rt.id
          WHERE
            rt.cloudService="DATAPROC" AND
            NOT c1.status="DELETED" AND
            c1.id != 6220
         """
      .query[RuntimeWithWorkers]

  def impl[F[_]](xa: Transactor[F])(implicit F: Async[F]): DbReader[F] = new DbReader[F] {

    /**
     * AOU reuses runtime names, hence exclude any aou runtimes that have the same names that are still "alive"
     */
    override def getDeletedRuntimes: Stream[F, Runtime] =
      deletedRuntimeQuery.stream.transact(xa)

    override def getDeletingRuntimes: Stream[F, Runtime] =
      deletingRuntimeQuery.stream.transact(xa)

    override def getErroredRuntimes: Stream[F, Runtime] =
      erroredRuntimeQuery.stream.transact(xa)

    override def getStoppedRuntimes: Stream[F, Runtime] =
      stoppedRuntimeQuery.stream.transact(xa)

    override def getInitBucketsToDelete: Stream[F, InitBucketToRemove] =
      initBucketsToDeleteQuery.stream
        .transact(xa)

    // Same disk names might be re-used
    override def getDeletedDisks: Stream[F, Disk] =
      shouldBedeletedDisksQuery.stream.transact(xa)

    override def getDeletedAndErroredKubernetesClusters: Stream[F, KubernetesCluster] =
      deletedAndErroredKubernetesClusterQuery.stream.transact(xa)

    override def getDeletedAndErroredNodepools: Stream[F, Nodepool] =
      deletedAndErroredNodepoolQuery.stream.transact(xa)

    override def getRuntimesWithWorkers: Stream[F, RuntimeWithWorkers] =
      dataprocClusterWithWorkersQuery.stream.transact(xa)
  }
}

final case class BucketToRemove(googleProject: GoogleProject, bucket: Option[GcsBucketName]) {
  override def toString: String = s"${googleProject.value},${bucket.getOrElse("null")}"
}
