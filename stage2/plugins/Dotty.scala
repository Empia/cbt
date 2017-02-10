package cbt
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.FileTime

trait Dotty extends BaseBuild{
  def dottyVersion: String = "0.1-20160926-ec28ea1-NIGHTLY"
  def dottyOptions: Seq[String] = Seq()
  override def scalaTarget: File = target ++ s"/dotty-$dottyVersion"

  private lazy val dottyLib = new DottyLib(
    logger, context.cbtLastModified, context.paths.mavenCache,
    context.classLoaderCache, dottyVersion = dottyVersion
  )

  override def compile: Option[Long] = taskCache[Dotty]("compile").memoize{
    dottyLib.compile(
      sourceFiles, compileTarget, compileStatusFile, compileDependencies, dottyOptions
    )
  }

  def doc: ExitCode =
    dottyLib.doc(
      sourceFiles, compileClasspath, docTarget, dottyOptions
    )

  override def repl = dottyLib.repl(context.args, classpath)

  override def dependencies = Resolver(mavenCentral).bind(
    ScalaDependency( "org.scala-lang.modules", "scala-java8-compat", "0.8.0-RC7" )
  )
}

class DottyLib(
  logger: Logger,
  cbtLastModified: Long,
  mavenCache: File,
  classLoaderCache: ClassLoaderCache,
  dottyVersion: String
)(implicit transientCache: java.util.Map[AnyRef,AnyRef]){
  val lib = new Lib(logger)
  import lib._

  private def Resolver(urls: URL*) = MavenResolver(cbtLastModified, mavenCache, urls: _*)
  private lazy val dottyDependency = Resolver(mavenCentral).bindOne(
    MavenDependency("ch.epfl.lamp","dotty_2.11",dottyVersion)
  )

  def repl(args: Seq[String], classpath: ClassPath) = {
    consoleOrFail("Use `cbt direct repl` instead")
    lib.runMain(
      "dotty.tools.dotc.repl.Main",
      Seq(
        "-bootclasspath",
        dottyDependency.classpath.string,
        "-classpath",
        classpath.string
      ) ++ args,
      dottyDependency.classLoader(classLoaderCache)
    )
  }

  def doc(
    sourceFiles: Seq[File],
    dependencyClasspath: ClassPath,
    docTarget: File,
    compileArgs: Seq[String]
  ): ExitCode = {
    if(sourceFiles.isEmpty){
      ExitCode.Success
    } else {
      docTarget.mkdirs
      val args = Seq(
        // FIXME: can we use compiler dependency here?
        "-bootclasspath", dottyDependency.classpath.string, // FIXME: does this break for builds that don't have scalac dependencies?
        "-classpath", dependencyClasspath.string, // FIXME: does this break for builds that don't have scalac dependencies?
        "-d",  docTarget.toString
      ) ++ compileArgs ++ sourceFiles.map(_.toString)
      logger.lib("creating docs for source files "+args.mkString(", "))
      val exitCode = redirectOutToErr{
        runMain(
          "dotty.tools.dottydoc.api.java.Dottydoc",
          args,
          dottyDependency.classLoader(classLoaderCache),
          fakeInstance = true // this is a hack as Dottydoc's main method is not static
        )
      }
      System.err.println("done")
      exitCode
    }
  }

  def compile(
    sourceFiles: Seq[File],
    compileTarget: File,
    statusFile: File,
    dependencies: Seq[Dependency],
    dottyOptions: Seq[String]
  ): Option[Long] = {
    val d = Dependencies(dependencies)
    val classpath = d.classpath
    val cp = classpath.string
    if(classpath.files.isEmpty)
      throw new Exception("Trying to compile with empty classpath. Source files: " ++ sourceFiles.toString)

    if( sourceFiles.isEmpty ){
      None
    }else{
      val start = System.currentTimeMillis
      val lastCompiled = statusFile.lastModified
      if( d.lastModified > lastCompiled || sourceFiles.exists(_.lastModified > lastCompiled) ){

        val _class = "dotty.tools.dotc.Main"
        val dualArgs =
          Seq(
            "-d", compileTarget.toString
          )
        val singleArgs = dottyOptions.map( "-S" ++ _ )

        val code =
          try{
            System.err.println("Compiling with Dotty to " ++ compileTarget.toString)
            compileTarget.mkdirs
            redirectOutToErr{
              lib.runMain(
                _class,
                dualArgs ++ singleArgs ++ Seq(
                  "-bootclasspath", dottyDependency.classpath.string, // let's put cp last. It so long
                  "-classpath", classpath.string // let's put cp last. It so long
                ) ++ sourceFiles.map(_.toString),
                dottyDependency.classLoader(classLoaderCache)
              )
            }
          } catch {
            case e: Exception =>
            System.err.println(red("Dotty crashed. See https://github.com/lampepfl/dotty/issues. To reproduce run:"))
            System.out.println(s"""
java -cp \\
${dottyDependency.classpath.strings.mkString(":\\\n")} \\
\\
${_class} \\
\\
${dualArgs.grouped(2).map(_.mkString(" ")).mkString(" \\\n")} \\
\\
${singleArgs.mkString(" \\\n")} \\
\\
-bootclasspath \\
${dottyDependency.classpath.strings.mkString(":\\\n")} \\
-classpath \\
${classpath.strings.mkString(":\\\n")} \\
\\
${sourceFiles.sorted.mkString(" \\\n")}
"""
            )
            ExitCode.Failure
          }

        if(code == ExitCode.Success){
          // write version and when last compilation started so we can trigger
          // recompile if cbt version changed or newer source files are seen
          write(statusFile, "")//cbtVersion.getBytes)
          Files.setLastModifiedTime(statusFile.toPath, FileTime.fromMillis(start) )
        } else {
          System.exit(code.integer) // FIXME: let's find a better solution for error handling. Maybe a monad after all.
        }
        Some( start )
      } else {
        Some( lastCompiled )
      }
    }
  }
}
