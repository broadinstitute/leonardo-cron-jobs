package com.broadinstitute.dsp
package janitor

import cats.effect.IO
import fs2.Stream

class FakeDbReader extends DbReader[IO] {
  override def getStagingBucketsToDelete: Stream[IO, BucketToRemove] = Stream.empty
  override def getStuckAppToReport: Stream[IO, AppToReport] = Stream.empty
}
