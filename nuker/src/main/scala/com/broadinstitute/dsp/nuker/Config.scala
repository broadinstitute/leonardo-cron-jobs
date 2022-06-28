package com.broadinstitute.dsp
package nuker

import java.nio.file.Path

import cats.syntax.all._
import pureconfig._
import pureconfig.generic.auto._
import com.broadinstitute.dsp.ConfigImplicits._

object Config {
  val appConfig = ConfigSource.default
    .load[AppConfig]
    .leftMap(failures => new RuntimeException(failures.toList.map(_.description).mkString("\n")))
}

final case class AppConfig(pubsubTopicCleaner: PubsubTopicCleanerConfig, pathToCredential: Path, prometheus: Prometheus)
