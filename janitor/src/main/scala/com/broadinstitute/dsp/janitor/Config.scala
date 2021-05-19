package com.broadinstitute.dsp
package janitor

import java.nio.file.Path
import cats.syntax.all._
import pureconfig._
import pureconfig.generic.auto._
import com.broadinstitute.dsp.ConfigImplicits._
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GoogleProject}

object Config {
  val appConfig = ConfigSource.default
    .load[AppConfig]
    .leftMap(failures => new RuntimeException(failures.toList.map(_.description).mkString("\n")))
}

final case class PubsubConfig(googleProject: GoogleProject, topicName: String)
final case class AppConfig(database: DatabaseConfig,
                           pathToCredential: Path,
                           reportDestinationBucket: GcsBucketName,
                           runtimeCheckerConfig: RuntimeCheckerConfig,
                           leonardoPubsub: PubsubConfig)