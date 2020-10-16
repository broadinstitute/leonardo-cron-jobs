package com.broadinstitute.dsp
package resourceValidator

import fs2.Stream
import cats.effect.IO

class FakeDbReader extends DbReader[IO] {
  override def getDeletedRuntimes: Stream[IO, Runtime] = Stream.empty

  override def getErroredRuntimes: Stream[IO, Runtime] = Stream.empty

  override def getBucketsToDelete: Stream[IO, BucketToRemove] = Stream.empty

  override def getKubernetesClustersToDelete: Stream[IO, KubernetesClusterToRemove] = Stream.empty

  override def getDeletedDisks: Stream[IO, Disk] = Stream.empty
}
