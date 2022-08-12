import sbtassembly.{MergeStrategy, PathList}

object Merging {
  def customMergeStrategy(oldStrategy: (String) => MergeStrategy): (String => MergeStrategy) = {
    case PathList("io", "sundr", _ @_*)                       => MergeStrategy.first
    case PathList("org", "bouncycastle", _ @_*)               => MergeStrategy.first
    case PathList("META-INF", "versions", "9", _ @_*)         => MergeStrategy.discard
    case PathList("META-INF", "versions", "11", _ @_*)        => MergeStrategy.discard
    case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.concat
    case PathList("module-info.class")                        => MergeStrategy.concat
    case "reference.conf"                                     => MergeStrategy.concat
    case x                                                    => oldStrategy(x)
  }
}
