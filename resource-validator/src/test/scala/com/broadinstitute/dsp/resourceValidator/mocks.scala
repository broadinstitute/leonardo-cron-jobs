package com.broadinstitute.dsp
package resourceValidator

import fs2.Stream
import cats.effect.IO

class FakeDbReader extends DbReader[IO] {
  override def getDeletedRuntimes: Stream[IO, Runtime] = Stream.empty
  override def getDeletingRuntimes: Stream[IO, Runtime] = Stream.empty
  override def getErroredRuntimes: Stream[IO, Runtime] = Stream.empty
  override def getStoppedRuntimes: Stream[IO, Runtime] = Stream.empty

  override def getDeletedAndErroredKubernetesClusters: Stream[IO, KubernetesCluster] = Stream.empty
  override def getDeletedAndErroredNodepools: Stream[IO, Nodepool] = Stream.empty

  override def getInitBucketsToDelete: Stream[IO, InitBucketToRemove] = Stream.empty

  override def getDeletedDisks: Stream[IO, Disk] = Stream.empty

  override def getRuntimesWithWorkers: Stream[IO, RuntimeWithWorkers] = Stream.empty
}
