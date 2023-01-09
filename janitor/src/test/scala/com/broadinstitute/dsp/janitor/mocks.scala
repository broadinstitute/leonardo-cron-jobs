package com.broadinstitute.dsp
package janitor

import cats.effect.IO
import fs2.Stream

class FakeDbReader extends DbReader[IO] {
  override def getKubernetesClustersToDelete: Stream[IO, KubernetesClusterToRemove] = Stream.empty
  override def getNodepoolsToDelete: Stream[IO, Nodepool] = Stream.empty
  override def getStagingBucketsToDelete: Stream[IO, BucketToRemove] = Stream.empty
  override def getStuckAppToReport: Stream[IO, AppToReport] = Stream.empty
}
