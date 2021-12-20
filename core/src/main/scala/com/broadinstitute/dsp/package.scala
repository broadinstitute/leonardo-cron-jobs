package com.broadinstitute

import cats.effect.{Resource, Sync}
import com.google.auth.oauth2.{GoogleCredentials, ServiceAccountCredentials}
import org.broadinstitute.dsde.workbench.google2.RegionName

import java.nio.file.Path
import scala.jdk.CollectionConverters._

package object dsp {
  val supportedRegions = Set(
    "northamerica-northeast1",
    "southamerica-east1",
    "us-central1",
    "us-east1",
    "us-east4",
    "us-west1",
    "us-west2",
    "us-west3",
    "us-west4",
    "europe-central2",
    "europe-north1",
    "europe-west1",
    "europe-west2",
    "europe-west3",
    "europe-west4",
    "europe-west6",
    "asia-east1",
    "asia-east2",
    "asia-northeast1",
    "asia-northeast2",
    "asia-northeast3",
    "asia-south1",
    "asia-southeast1",
    "asia-southeast2",
    "australia-southeast1"
  ).map(s => RegionName(s))

  def initGoogleCredentials[F[_]: Sync](
    pathToCredential: Path
  ): Resource[F, GoogleCredentials] =
    for {
      credentialFile <- org.broadinstitute.dsde.workbench.util2.readFile[F](pathToCredential.toString)
      credential <- Resource.eval(Sync[F].delay(ServiceAccountCredentials.fromStream(credentialFile)))
    } yield credential.createScoped(Seq("https://www.googleapis.com/auth/cloud-platform").asJava)
}
