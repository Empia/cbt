import cbt._

// cbt:https://github.com/cvogt/cbt.git#e8673866b79f7473391dcee26243eee80d5d3cb6
class Build(val context: Context) extends BaseBuild with PackageJars{
  def groupId = "cbt.test"
  def version = "0.1"
  def name = "library-test"

  override def dependencies =
    super.dependencies ++ // don't forget super.dependencies here for scala-library, etc.
    Seq(
      // source dependency
      // DirectoryDependency( projectDirectory ++ "/subProject" )
    ) ++
    // pick resolvers explicitly for individual dependencies (and their transitive dependencies)
    Resolver( mavenCentral, sonatypeReleases ).bind(
      // CBT-style Scala dependencies
      // ScalaDependency( "com.lihaoyi", "ammonite-ops", "0.5.5" )
      // MavenDependency( "com.lihaoyi", "ammonite-ops_2.11", "0.5.5" )

      // SBT-style dependencies
      // "com.lihaoyi" %% "ammonite-ops" % "0.5.5"
      // "com.lihaoyi" % "ammonite-ops_2.11" % "0.5.5"
    )
}
