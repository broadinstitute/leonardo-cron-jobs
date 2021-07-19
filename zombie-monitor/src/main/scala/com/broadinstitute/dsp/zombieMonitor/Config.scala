package com.broadinstitute.dsp
package zombieMonitor

import java.nio.file.Path

import cats.syntax.all._
import org.broadinstitute.dsde.workbench.model.google.GcsBucketName
import pureconfig._
import pureconfig.generic.auto._
import ConfigImplicits._

object Config {

  val appConfig = ConfigSource.default
    .load[AppConfig]
    .leftMap(failures => new RuntimeException(failures.toList.map(_.description).mkString("\n")))
}

final case class AppConfig(database: DatabaseConfig,
                           pathToCredential: Path,
                           reportDestinationBucket: GcsBucketName,
                           runtimeCheckerConfig: RuntimeCheckerConfig
)
