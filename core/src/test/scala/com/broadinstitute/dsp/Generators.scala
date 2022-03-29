package com.broadinstitute.dsp

import cats.data.NonEmptyList
import org.broadinstitute.dsde.workbench.google2.GKEModels.{KubernetesClusterId, KubernetesClusterName}
import org.scalacheck.{Arbitrary, Gen}
import org.broadinstitute.dsde.workbench.google2.Generators._

object Generators {
  val genCloudService: Gen[CloudService] = Gen.oneOf(CloudService.Gce, CloudService.Dataproc, CloudService.AzureVM)
  def genRuntime(possibleStatuses: Option[NonEmptyList[String]]): Gen[Runtime] = for {
    id <- Gen.chooseNum(0, 100)
    cloudService <- genCloudService
    project <- genGoogleProject
    runtimeName <- Gen.uuid.map(_.toString)
    status <- possibleStatuses.fold(Gen.oneOf("Running", "Creating", "Deleted", "Error"))(s => Gen.oneOf(s.toList))
  } yield cloudService match {
    case CloudService.Dataproc =>
      Runtime.Dataproc(id, project, runtimeName, cloudService, status, DBTestHelper.regionName)
    case CloudService.Gce =>
      Runtime.Gce(id, project, runtimeName, cloudService, status, DBTestHelper.zoneName)
    case CloudService.AzureVM =>
      Runtime.AzureVM(id, runtimeName, cloudService, status)
  }
  val genDataprocRuntime: Gen[Runtime.Dataproc] = for {
    id <- Gen.chooseNum(0, 100)
    project <- genGoogleProject
    runtimeName <- Gen.uuid.map(_.toString)
    status <- Gen.oneOf("Running", "Creating", "Deleted", "Error")
  } yield Runtime.Dataproc(id, project, runtimeName, CloudService.Dataproc, status, DBTestHelper.regionName)
  val genDisk: Gen[Disk] = for {
    id <- Gen.chooseNum(0, 100)
    project <- genGoogleProject
    diskName <- genDiskName
    zone <- genZoneName
  } yield Disk(id,
               CloudContext.Gcp(project),
               diskName,
               zone,
               formattedBy = Some("GCE")
  )

  val genInitBucket: Gen[InitBucketToRemove] = for {
    project <- genGoogleProject
    bucketName <- genGcsBucketName
  } yield InitBucketToRemove(project, Some(InitBucketName(bucketName.value)))

  val genKubernetesCluster: Gen[KubernetesCluster] = for {
    name <- Gen.uuid.map(x => KubernetesClusterName(x.toString))
    project <- genGoogleProject
    location <- genLocation
  } yield KubernetesCluster(name, project, location)

  val genNodepool: Gen[Nodepool] = for {
    id <- Gen.chooseNum(0, 100)
    nodepoolName <- genNodepoolName
    clusterName <- Gen.uuid.map(x => KubernetesClusterName(x.toString))
    project <- genGoogleProject
    location <- genLocation
  } yield Nodepool(id, nodepoolName, clusterName, project, location)

  val genK8sClusterToScan: Gen[K8sClusterToScan] = for {
    id <- Gen.chooseNum(0, 100)
    clusterId <- genKubernetesClusterId
  } yield K8sClusterToScan(id, clusterId)

  val genNodepoolToScan: Gen[NodepoolToScan] = for {
    id <- Gen.chooseNum(0, 100)
    nodepoolId <- genNodepoolId
  } yield NodepoolToScan(id, nodepoolId)

  val genKubernetesClusterToRemove: Gen[KubernetesClusterToRemove] = for {
    id <- Gen.chooseNum(0, 100)
    googleProject <- genGoogleProject
  } yield KubernetesClusterToRemove(id, googleProject)

  val genRuntimeWithWorkers: Gen[RuntimeWithWorkers] = for {
    runtime <- genDataprocRuntime
    num1 <- Gen.chooseNum(1, 100)
    num2 <- Gen.chooseNum(1, 100)
  } yield RuntimeWithWorkers(runtime, WorkerConfig(Some(num1), Some(num2)))

  val genRemovableNodepoolStatus: Gen[RemovableNodepoolStatus] = for {
    status <- Gen.oneOf(RemovableNodepoolStatus.removableStatuses)
  } yield status

  val arbDataprocRuntime: Arbitrary[Runtime.Dataproc] = Arbitrary(genDataprocRuntime)
  implicit val arbRuntime: Arbitrary[Runtime] = Arbitrary(genRuntime(None))
  implicit val arbCloudService: Arbitrary[CloudService] = Arbitrary(genCloudService)
  implicit val arbDisk: Arbitrary[Disk] = Arbitrary(genDisk)
  implicit val arbInitBucket: Arbitrary[InitBucketToRemove] = Arbitrary(genInitBucket)
  implicit val arbKubernetesClusterId: Arbitrary[KubernetesClusterId] = Arbitrary(genKubernetesClusterId)
  implicit val arbK8sClusterToScan: Arbitrary[K8sClusterToScan] = Arbitrary(genK8sClusterToScan)
  implicit val arbRemovableNodepoolStatus: Arbitrary[RemovableNodepoolStatus] = Arbitrary(genRemovableNodepoolStatus)
  implicit val arbNodepoolToScan: Arbitrary[NodepoolToScan] = Arbitrary(genNodepoolToScan)
  implicit val arbKubernetesClusterToRemove: Arbitrary[KubernetesClusterToRemove] = Arbitrary(
    genKubernetesClusterToRemove
  )
  implicit val arbKubernetesCluster: Arbitrary[KubernetesCluster] = Arbitrary(genKubernetesCluster)
  implicit val arbNodepool: Arbitrary[Nodepool] = Arbitrary(genNodepool)
  implicit val arbRuntimeWithWorkers: Arbitrary[RuntimeWithWorkers] = Arbitrary(genRuntimeWithWorkers)
}
