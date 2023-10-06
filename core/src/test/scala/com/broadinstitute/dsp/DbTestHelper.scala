package com.broadinstitute.dsp

import cats.effect.{IO, Resource}
import com.broadinstitute.dsp.DbReaderImplicits._
import doobie.Put
import doobie.implicits._
import doobie.util.transactor.Transactor
import org.broadinstitute.dsde.workbench.google2.KubernetesSerializableName.NamespaceName
import org.broadinstitute.dsde.workbench.google2.{RegionName, ZoneName}
import org.scalatest.Tag

import java.time.Instant

object DbTestHelper {
  implicit val cloudServicePut: Put[CloudService] = Put[String].contramap(cloudService => cloudService.asString)
  val zoneName = ZoneName("us-central1-a")
  val regionName = RegionName("us-central1")

  def yoloTransactor(implicit databaseConfig: DatabaseConfig): Transactor[IO] =
    Transactor.fromDriverManager[IO](
      driver = "com.mysql.cj.jdbc.Driver", // driver classname
      databaseConfig.url,
      databaseConfig.user,
      databaseConfig.password,
      logHandler = None
    )

  def transactorResource(implicit
    xa: Transactor[IO]
  ): Resource[IO, Unit] =
    for {
      _ <- Resource.make(IO.unit)(_ => truncateTables(xa))
    } yield ()

  def insertDiskQuery(disk: Disk, status: String) =
    sql"""INSERT INTO PERSISTENT_DISK
         (cloudContext, cloudProvider, zone, name, googleId, samResourceId, status, creator, createdDate, destroyedDate, dateAccessed, sizeGb, type, blockSizeBytes, serviceAccount, formattedBy)
         VALUES (${disk.cloudContext.asString}, ${disk.cloudContext.cloudProvider}, ${disk.zone}, ${disk.diskName}, "fakeGoogleId", "fakeSamResourceId", ${status}, "fake@broadinstitute.org", now(), now(), now(), 50, "Standard", "4096", "pet@broadinsitute.org", "GCE")
         """.update

  def insertDisk(disk: Disk, status: String = "Ready")(implicit xa: Transactor[IO]): IO[Long] =
    insertDiskQuery(disk, status: String).withUniqueGeneratedKeys[Long]("id").transact(xa)

  def insertK8sCluster(cluster: KubernetesCluster, status: String = "RUNNING")(implicit
    xa: Transactor[IO]
  ): IO[Long] =
    sql"""INSERT INTO KUBERNETES_CLUSTER
         (cloudContext, clusterName, location, status, creator, createdDate, destroyedDate, dateAccessed, loadBalancerIp, networkName, subNetworkName, subNetworkIpRange, region, apiServerIp, ingressChart)
         VALUES (${cluster.cloudContext.asString}, ${cluster.clusterName}, ${cluster.location}, ${status}, "fake@broadinstitute.org", now(), now(), now(), "0.0.0.1", "network", "subnetwork", "0.0.0.1/20", ${regionName}, "35.202.56.6", "stable/nginx-ingress-1.41.3")
         """.update.withUniqueGeneratedKeys[Long]("id").transact(xa)

  def insertNodepool(clusterId: Long, nodepoolName: String, isDefault: Boolean, status: String = "RUNNING")(implicit
    xa: Transactor[IO]
  ): IO[Long] =
    sql"""INSERT INTO NODEPOOL
         (clusterId, nodepoolName, status, creator, createdDate, destroyedDate, dateAccessed, machineType, numNodes, autoScalingMin, autoScalingMax, isDefault)
         VALUES (${clusterId}, ${nodepoolName}, ${status}, "fake@broadinstitute.org", now(), now(), now(), "n1-standard-1", 1, 0, 1, ${isDefault})
         """.update.withUniqueGeneratedKeys[Long]("id").transact(xa)

  def insertRuntime(runtime: Runtime,
                    runtimeConfigId: Long,
                    createdDate: Instant = Instant.now(),
                    dateAccessed: Instant = Instant.now()
  )(implicit
    xa: Transactor[IO]
  ): IO[Long] =
    sql"""INSERT INTO CLUSTER
         (runtimeName,
         cloudContext,
         cloudProvider,
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
         runtimeConfigId)
         VALUES (
         ${runtime.runtimeName},
         ${runtime.cloudContext.asString},
         ${runtime.cloudContext.cloudProvider},
         "op1",
         ${runtime.status},
         "fakeIp",
         $createdDate,
         now(),
         "gs://initBucket",
         "fake@broadinstitute.org",
         "pet@broadinstitute.org",
         "stagingBucket",
         $dateAccessed,
         30,
         "clientId",
         now(),
         true,
         "internalId",
         $runtimeConfigId)
         """.update.withUniqueGeneratedKeys[Long]("id").transact(xa)

  def insertRuntimeConfig(cloudService: CloudService, dateAccessed: Instant = Instant.now())(implicit
    xa: Transactor[IO]
  ): IO[Long] = {
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
         $dateAccessed,
         30,
         ${zone},
         ${region}
         )
         """.update.withUniqueGeneratedKeys[Long]("id").transact(xa)
  }

  def insertNamespace(clusterId: Long, namespaceName: NamespaceName)(implicit
    xa: Transactor[IO]
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
    xa: Transactor[IO]
  ): IO[Long] =
    sql"""INSERT INTO APP
         (nodepoolId, appType, appName, status, samResourceId, creator, createdDate, destroyedDate, dateAccessed, namespaceId, diskId, customEnvironmentVariables, googleServiceAccount, kubernetesServiceAccount, chart, `release`)
         VALUES (${nodepoolId}, "GALAXY", ${appName}, ${status}, "samId", "fake@broadinstitute.org", ${createdDate}, ${destroyedDate}, now(), ${namespaceId}, ${diskId}, "", "gsa", "ksa", "chart1", "release1")
         """.update.withUniqueGeneratedKeys[Long]("id").transact(xa)

  def insertAppUsage(appId: Long)(implicit
    xa: Transactor[IO]
  ): IO[Int] =
    sql"""
        INSERT INTO APP_USAGE (appId, startTime, stopTime) values (${appId}, "2023-09-28 17:24:29.568559", "1970-01-01 00:00:01.000000")
       """.update.run.transact(xa)

  def getDiskStatus(diskId: Long)(implicit xa: Transactor[IO]): IO[String] =
    sql"""
         SELECT status FROM PERSISTENT_DISK where id = ${diskId}
         """.query[String].unique.transact(xa)

  def getK8sClusterStatus(id: Long)(implicit xa: Transactor[IO]): IO[String] =
    sql"""
         SELECT status FROM KUBERNETES_CLUSTER where id = ${id}
         """.query[String].unique.transact(xa)

  def getNodepoolStatus(id: Long)(implicit xa: Transactor[IO]): IO[String] =
    sql"""
         SELECT status FROM NODEPOOL where id = ${id}
         """.query[String].unique.transact(xa)

  def getAppUsageStopTime(appId: Long)(implicit xa: Transactor[IO]): IO[Instant] =
    sql"""
         SELECT stopTime FROM APP_USAGE where appId = ${appId}
         """.query[Instant].unique.transact(xa)

  def getAppDateAccessed(id: Long)(implicit xa: Transactor[IO]): IO[Instant] =
    sql"""
         SELECT dateAccessed FROM APP where id = ${id}
         """.query[Instant].unique.transact(xa)

  def getAppStatus(id: Long)(implicit xa: Transactor[IO]): IO[String] =
    sql"""
         SELECT status FROM APP where id = ${id}
         """.query[String].unique.transact(xa)

  def getRuntimeStatus(id: Long)(implicit xa: Transactor[IO]): IO[String] =
    sql"""
         SELECT status FROM CLUSTER where id = ${id}
         """.query[String].unique.transact(xa)

  def getRuntimeError(runtimeId: Long)(implicit xa: Transactor[IO]): IO[RuntimeError] =
    sql"""
         SELECT errorCode, errorMessage FROM CLUSTER_ERROR where clusterId = ${runtimeId}
         """.query[RuntimeError].unique.transact(xa)

  def getK8sClusterName(id: Long)(implicit xa: Transactor[IO]): IO[String] =
    sql"""
         SELECT clusterName FROM KUBERNETES_CLUSTER where id = ${id}
         """.query[String].unique.transact(xa)

  def getPdIdFromRuntimeConfig(id: Long)(implicit xa: Transactor[IO]): IO[Option[Long]] =
    sql"""
         SELECT persistentDiskId FROM RUNTIME_CONFIG where id = ${id}
         """.query[Option[Long]].unique.transact(xa)

  def getPdIdFromK8sCluster(id: Long)(implicit xa: Transactor[IO]): IO[Option[Long]] =
    sql"""
         SELECT diskId FROM APP where id = ${id}
         """.query[Option[Long]].unique.transact(xa)

  def getNodepoolName(id: Long)(implicit xa: Transactor[IO]): IO[String] =
    sql"""
         SELECT nodepoolName FROM NODEPOOL where id = ${id}
         """.query[String].unique.transact(xa)

  private def truncateTables(xa: Transactor[IO]): IO[Unit] = {
    val res = for {
      _ <- sql"Delete from APP_USAGE".update.run
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
