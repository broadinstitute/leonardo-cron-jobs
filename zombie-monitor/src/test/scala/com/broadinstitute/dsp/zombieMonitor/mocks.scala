package com.broadinstitute.dsp.zombieMonitor

import cats.effect.IO
import com.broadinstitute.dsp
import com.broadinstitute.dsp.{Disk, GceGalaxyApp}
import fs2.Stream

class FakeDbReader extends DbReader[IO] {
  override def getDisksToDeleteCandidate: Stream[IO, Disk] = Stream.empty
  override def getGceGalaxyAppsToDeleteCandidate: Stream[IO, GceGalaxyApp] = Stream.empty
  override def getRuntimeCandidate: Stream[IO, dsp.Runtime] = Stream.empty
  override def updateDiskStatus(id: Long): IO[Unit] = IO.unit
  override def markGalaxyAppDeleted(appId: Long): IO[Unit] = IO.unit
  override def markRuntimeDeleted(id: Long): IO[Unit] = IO.unit
  override def updateRuntimeStatus(id: Long, status: String): IO[Unit] = IO.unit
  override def insertClusterError(clusterId: Long, errorCode: Option[Int], errorMessage: String): IO[Unit] = IO.unit
  override def updateRuntimeDeletedFrom(runtimeId: Long, deletedFrom: String): IO[Unit] = IO.unit

}
