import sbtassembly.{MergeStrategy, PathList}

object Merging {
  def customMergeStrategy(oldStrategy: (String) => MergeStrategy): (String => MergeStrategy) = {
    case PathList("META-INF", "versions", "9", _ @_*)         => MergeStrategy.discard
    case PathList("META-INF", "versions", "11", _ @_*)        => MergeStrategy.discard
    case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.concat
    case PathList("module-info.class")                        => MergeStrategy.concat
    case PathList("META-INF", "okio.kotlin_module")           => MergeStrategy.first
    case "reference.conf"                                     => MergeStrategy.concat
    case x =>
      oldStrategy(x)
  }
}
