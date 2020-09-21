addSbtPlugin("com.geirsson" % "sbt-ci-release" % "1.5.3")
addSbtPlugin("com.github.alexarchambault.tmp" % "sbt-mima-plugin" % "0.7.1-SNAPSHOT")
addSbtPlugin("org.xerial.sbt" % "sbt-pack" % "0.13")

resolvers += Resolver.sonatypeRepo("snapshots")
