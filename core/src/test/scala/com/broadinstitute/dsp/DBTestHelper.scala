package com.broadinstitute.dsp

import java.time.Instant
import java.util.UUID
import cats.effect.{ContextShift, IO, Resource}
import doobie.hikari.HikariTransactor
import doobie.implicits._
import DbReaderImplicits._
import doobie.Put
import doobie.util.transactor.Transactor
import org.broadinstitute.dsde.workbench.google2.GKEModels.KubernetesClusterId
import org.broadinstitute.dsde.workbench.google2.KubernetesSerializableName.NamespaceName
import org.broadinstitute.dsde.workbench.google2.{RegionName, ZoneName}
import org.scalatest.Tag

object DBTestHelper {
  implicit val cloudServicePut: Put[CloudService] = Put[String].contramap(cloudService => cloudService.asString)
  val zoneName = ZoneName("us-central1-a")
  val regionName = RegionName("us-central1")

  def yoloTransactor(implicit cs: ContextShift[IO], databaseConfig: DatabaseConfig): Transactor[IO] =
    Transactor.fromDriverManager[IO](
      "com.mysql.cj.jdbc.Driver", // driver classname
      databaseConfig.url,
      databaseConfig.user,
      databaseConfig.password
    )

  def transactorResource(implicit
    cs: ContextShift[IO],
    databaseConfig: DatabaseConfig
  ): Resource[IO, HikariTransactor[IO]] =
    for {
      xa <- DbTransactor.init[IO](databaseConfig)
      _ <- Resource.eval(truncateTables(xa))
    } yield xa

  def insertDiskQuery(disk: Disk, status: String) =
    sql"""INSERT INTO PERSISTENT_DISK
         (googleProject, zone, name, googleId, samResourceId, status, creator, createdDate, destroyedDate, dateAccessed, sizeGb, type, blockSizeBytes, serviceAccount, formattedBy)
         VALUES (${disk.googleProject}, ${disk.zone}, ${disk.diskName}, "fakeGoogleId", "fakeSamResourceId", ${status}, "fake@broadinstitute.org", now(), now(), now(), 50, "Standard", "4096", "pet@broadinsitute.org", "GCE")
         """.update

  def insertDisk(disk: Disk, status: String = "Ready")(implicit xa: HikariTransactor[IO]): IO[Long] =
    insertDiskQuery(disk, status: String).withUniqueGeneratedKeys[Long]("id").transact(xa)

  def insertK8sCluster(clusterId: KubernetesClusterId, status: String = "RUNNING")(implicit
    xa: HikariTransactor[IO]
  ): IO[Long] =
    sql"""INSERT INTO KUBERNETES_CLUSTER
         (googleProject, clusterName, location, status, creator, createdDate, destroyedDate, dateAccessed, loadBalancerIp, networkName, subNetworkName, subNetworkIpRange, region, apiServerIp, ingressChart)
         VALUES (${clusterId.project}, ${clusterId.clusterName}, ${clusterId.location}, ${status}, "fake@broadinstitute.org", now(), now(), now(), "0.0.0.1", "network", "subnetwork", "0.0.0.1/20", ${regionName}, "35.202.56.6", "stable/nginx-ingress-1.41.3")
         """.update.withUniqueGeneratedKeys[Long]("id").transact(xa)

  def insertNodepool(clusterId: Long, nodepoolName: String, isDefault: Boolean, status: String = "RUNNING")(implicit
    xa: HikariTransactor[IO]
  ): IO[Long] =
    sql"""INSERT INTO NODEPOOL
         (clusterId, nodepoolName, status, creator, createdDate, destroyedDate, dateAccessed, machineType, numNodes, autoScalingMin, autoScalingMax, isDefault)
         VALUES (${clusterId}, ${nodepoolName}, ${status}, "fake@broadinstitute.org", now(), now(), now(), "n1-standard-1", 1, 0, 1, ${isDefault})
         """.update.withUniqueGeneratedKeys[Long]("id").transact(xa)

  def insertRuntime(runtime: Runtime, runtimeConfigId: Long, createdDate: Instant = Instant.now())(implicit
    xa: HikariTransactor[IO]
  ): IO[Long] =
    sql"""INSERT INTO CLUSTER
         (clusterName,
         googleProject,
         operationName,
         status,
         hostIp,
         createdDate,
         destroyedDate,
         initBucket,
         creator,
         serviceAccount,
         stagingBucket,
         dateAccessed,
         autopauseThreshold,
         defaultClientId,
         kernelFoundBusyDate,
         welderEnabled,
         internalId,
         runtimeConfigId,
         googleId)
         VALUES (
         ${runtime.runtimeName},
         ${runtime.googleProject},
         "op1",
         ${runtime.status},
         "fakeIp",
         $createdDate,
         now(),
         "gs://initBucket",
         "fake@broadinstitute.org",
         "pet@broadinstitute.org",
         "stagingBucket",
         now(),
         30,
         "clientId",
         now(),
         true,
         "internalId",
         $runtimeConfigId,
         ${UUID.randomUUID().toString})
         """.update.withUniqueGeneratedKeys[Long]("id").transact(xa)

  def insertRuntimeConfig(cloudService: CloudService)(implicit xa: HikariTransactor[IO]): IO[Long] = {
    val zone = if (cloudService == CloudService.Gce) Some(zoneName.value) else None
    val region = if (cloudService == CloudService.Dataproc) Some(regionName.value) else None
    sql"""INSERT INTO RUNTIME_CONFIG
         (cloudService,
          machineType,
          diskSize,
          numberOfWorkers,
          dateAccessed,
          bootDiskSize,
          zone,
          region
         )
         VALUES (
         ${cloudService},
         "n1-standard-4",
         100,
         0,
         now(),
         30,
         ${zone},
         ${region}
         )
         """.update.withUniqueGeneratedKeys[Long]("id").transact(xa)
  }

  def insertNamespace(clusterId: Long, namespaceName: NamespaceName)(implicit
    xa: HikariTransactor[IO]
  ): IO[Long] =
    sql"""INSERT INTO NAMESPACE
         (clusterId, namespaceName)
         VALUES (${clusterId}, ${namespaceName})
         """.update.withUniqueGeneratedKeys[Long]("id").transact(xa)

  def insertApp(nodepoolId: Long,
                namespaceId: Long,
                appName: String,
                diskId: Long,
                status: String = "RUNNING",
                createdDate: Instant = Instant.now(),
                destroyedDate: Instant = Instant.now()
  )(implicit
    xa: HikariTransactor[IO]
  ): IO[Long] =
    sql"""INSERT INTO APP
         (nodepoolId, appType, appName, status, samResourceId, creator, createdDate, destroyedDate, dateAccessed, namespaceId, diskId, customEnvironmentVariables, googleServiceAccount, kubernetesServiceAccount, chart, `release`)
         VALUES (${nodepoolId}, "GALAXY", ${appName}, ${status}, "samId", "fake@broadinstitute.org", ${createdDate}, ${destroyedDate}, now(), ${namespaceId}, ${diskId}, "", "gsa", "ksa", "chart1", "release1")
         """.update.withUniqueGeneratedKeys[Long]("id").transact(xa)

  def getDiskStatus(diskId: Long)(implicit xa: HikariTransactor[IO]): IO[String] =
    sql"""
         SELECT status FROM PERSISTENT_DISK where id = ${diskId}
         """.query[String].unique.transact(xa)

  def getK8sClusterStatus(id: Long)(implicit xa: HikariTransactor[IO]): IO[String] =
    sql"""
         SELECT status FROM KUBERNETES_CLUSTER where id = ${id}
         """.query[String].unique.transact(xa)

  def getNodepoolStatus(id: Long)(implicit xa: HikariTransactor[IO]): IO[String] =
    sql"""
         SELECT status FROM NODEPOOL where id = ${id}
         """.query[String].unique.transact(xa)

  def getAppStatus(id: Long)(implicit xa: HikariTransactor[IO]): IO[String] =
    sql"""
         SELECT status FROM APP where id = ${id}
         """.query[String].unique.transact(xa)

  def getRuntimeStatus(id: Long)(implicit xa: HikariTransactor[IO]): IO[String] =
    sql"""
         SELECT status FROM CLUSTER where id = ${id}
         """.query[String].unique.transact(xa)

  def getRuntimeError(runtimeId: Long)(implicit xa: HikariTransactor[IO]): IO[RuntimeError] =
    sql"""
         SELECT errorCode, errorMessage FROM CLUSTER_ERROR where clusterId = ${runtimeId}
         """.query[RuntimeError].unique.transact(xa)

  def getK8sClusterName(id: Long)(implicit xa: HikariTransactor[IO]): IO[String] =
    sql"""
         SELECT clusterName FROM KUBERNETES_CLUSTER where id = ${id}
         """.query[String].unique.transact(xa)

  def getPdIdFromRuntimeConfig(id: Long)(implicit xa: HikariTransactor[IO]): IO[Option[Long]] =
    sql"""
         SELECT persistentDiskId FROM RUNTIME_CONFIG where id = ${id}
         """.query[Option[Long]].unique.transact(xa)

  def getPdIdFromK8sCluster(id: Long)(implicit xa: HikariTransactor[IO]): IO[Option[Long]] =
    sql"""
         SELECT diskId FROM APP where id = ${id}
         """.query[Option[Long]].unique.transact(xa)

  def getNodepoolName(id: Long)(implicit xa: HikariTransactor[IO]): IO[String] =
    sql"""
         SELECT nodepoolName FROM NODEPOOL where id = ${id}
         """.query[String].unique.transact(xa)

  private def truncateTables(xa: HikariTransactor[IO]): IO[Unit] = {
    val res = for {
      _ <- sql"Delete from APP".update.run
      _ <- sql"Delete from NAMESPACE".update.run
      _ <- sql"Delete from NODEPOOL".update.run
      _ <- sql"Delete from PERSISTENT_DISK".update.run
      _ <- sql"Delete from NODEPOOL".update.run
      _ <- sql"Delete from KUBERNETES_CLUSTER".update.run
      _ <- sql"Delete from CLUSTER_ERROR".update.run
      _ <- sql"Delete from CLUSTER".update.run
      _ <- sql"Delete from RUNTIME_CONFIG".update.run
    } yield ()
    res.transact(xa)
  }
}

final case class RuntimeError(errorCode: Option[Int], errorMessage: String)

object DbTest extends Tag("cronJobs.dbTest")
