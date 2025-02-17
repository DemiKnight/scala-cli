package scala.build.tests

import bloop.rifle.BloopRifleLogger
import com.eed3si9n.expecty.Expecty.expect
import coursier.cache.CacheLogger
import org.scalajs.logging.{NullLogger, Logger as ScalaJsLogger}

import java.io.PrintStream
import scala.build.Ops.*
import scala.build.errors.{BuildException, Diagnostic, Severity}
import scala.build.input.Inputs
import scala.build.internals.FeatureType
import scala.build.options.{
  BuildOptions,
  InternalOptions,
  JavaOptions,
  ScalaOptions,
  ScalacOpt,
  Scope,
  ShadowingSeq
}
import scala.build.{Build, LocalRepo, Logger, Position, Positioned, Sources}

class BuildProjectTests extends munit.FunSuite {

  class LoggerMock extends Logger {

    var diagnostics: List[Diagnostic] = Nil

    override def error(message: String): Unit = ???

    override def message(message: => String): Unit = ???

    override def log(s: => String): Unit = ???

    override def log(s: => String, debug: => String): Unit = ???

    override def debug(s: => String): Unit = {}

    override def log(diagnostics: Seq[Diagnostic]): Unit = {
      this.diagnostics = this.diagnostics ++ diagnostics
    }

    override def log(ex: BuildException): Unit   = {}
    override def debug(ex: BuildException): Unit = {}

    override def exit(ex: BuildException): Nothing = ???

    override def coursierLogger(message: String): CacheLogger = CacheLogger.nop

    override def bloopRifleLogger: BloopRifleLogger = BloopRifleLogger.nop
    override def scalaJsLogger: ScalaJsLogger       = NullLogger

    override def scalaNativeTestLogger: scala.scalanative.build.Logger =
      scala.scalanative.build.Logger.nullLogger

    override def scalaNativeCliInternalLoggerOptions: List[String] =
      List()

    override def compilerOutputStream: PrintStream = ???

    override def verbosity = ???

    override def experimentalWarning(featureName: String, featureType: FeatureType): Unit = ???
    override def flushExperimentalWarnings: Unit                                          = ???
  }

  val bloopJavaPath = Position.Bloop("/home/empty/jvm/8/")

  def testJvmReleaseIsSetCorrectly(
    javaHome: String,
    bloopJvmVersion: Option[Int],
    scalacOptions: Seq[String] = Nil
  ) = {
    val options = BuildOptions(
      internal = InternalOptions(localRepository =
        LocalRepo.localRepo(scala.build.Directories.default().localRepoDir)
      ),
      javaOptions = JavaOptions(
        javaHomeOpt = Some(Positioned.none(os.Path(javaHome)))
      ),
      scalaOptions = ScalaOptions(
        scalacOptions = ShadowingSeq.from(
          scalacOptions.map(ScalacOpt(_)).map(Positioned.commandLine(_))
        )
      )
    )

    val inputs    = Inputs.empty("project")
    val sources   = Sources(Nil, Nil, None, Nil, options)
    val logger    = new LoggerMock()
    val artifacts = options.artifacts(logger, Scope.Test).orThrow
    val res = Build.buildProject(
      inputs,
      sources,
      Nil,
      options,
      bloopJvmVersion.map(bv => Positioned(bloopJavaPath, bv)),
      Scope.Test,
      logger,
      artifacts
    )

    val scalaCompilerOptions = res.fold(throw _, identity)
      .scalaCompiler
      .toSeq
      .flatMap(_.scalacOptions)
    (scalaCompilerOptions, res.fold(throw _, identity).javacOptions, logger.diagnostics)
  }

  def jvm(v: Int) = os.proc(TestUtil.cs, "java-home", "--jvm", s"zulu:$v").call().out.trim()

  test("Compiler options contain target JVM release") {
    val javaHome        = jvm(8)
    val bloopJvmVersion = 11
    val (scalacOptions, javacOptions, diagnostics) =
      testJvmReleaseIsSetCorrectly(javaHome, Some(bloopJvmVersion))
    expect(scalacOptions.containsSlice(Seq("-release", "8")))
    expect(javacOptions.containsSlice(Seq("--release", "8")))
    expect(diagnostics.isEmpty)

  }

  test("Empty BuildOptions is actually empty 2 ") {
    val javaHome        = jvm(8)
    val bloopJvmVersion = 8
    val (scalacOptions, javacOptions, diagnostics) =
      testJvmReleaseIsSetCorrectly(javaHome, Some(bloopJvmVersion))
    expect(!scalacOptions.containsSlice(Seq("-release")))
    expect(!javacOptions.containsSlice(Seq("--release")))
    expect(diagnostics.isEmpty)
  }

  test("Empty BuildOptions is actually empty 2 ") {
    val javaHome        = jvm(11)
    val bloopJvmVersion = 17
    val (scalacOptions, javacOptions, diagnostics) =
      testJvmReleaseIsSetCorrectly(javaHome, Some(bloopJvmVersion))
    expect(scalacOptions.containsSlice(Seq("-release", "11")))
    expect(javacOptions.containsSlice(Seq("--release", "11")))
    expect(diagnostics.isEmpty)
  }

  lazy val expectedDiagnostic = Diagnostic(
    Diagnostic.Messages.bloopTooOld,
    Severity.Warning,
    List(bloopJavaPath)
  )

  test("Compiler options contain target JVM release") {
    val javaHome        = jvm(17)
    val bloopJvmVersion = 11
    val (scalacOptions, javacOptions, diagnostics) =
      testJvmReleaseIsSetCorrectly(javaHome, Some(bloopJvmVersion))
    expect(!scalacOptions.containsSlice(Seq("-release")))
    expect(!javacOptions.containsSlice(Seq("--release")))
    expect(diagnostics == List(expectedDiagnostic))
  }

  test("Empty BuildOptions is actually empty 2 ") {
    val javaHome        = jvm(11)
    val bloopJvmVersion = 8
    val (scalacOptions, javacOptions, diagnostics) =
      testJvmReleaseIsSetCorrectly(javaHome, Some(bloopJvmVersion), List("-release", "17"))
    expect(scalacOptions.containsSlice(Seq("-release", "17")))
    expect(!javacOptions.containsSlice(Seq("--release")))
    expect(diagnostics == List(expectedDiagnostic))
  }

  test("workspace for bsp") {
    val options = BuildOptions(
      internal = InternalOptions(localRepository =
        LocalRepo.localRepo(scala.build.Directories.default().localRepoDir)
      )
    )
    val inputs    = Inputs.empty("project")
    val sources   = Sources(Nil, Nil, None, Nil, options)
    val logger    = new LoggerMock()
    val artifacts = options.artifacts(logger, Scope.Test).orThrow

    val project =
      Build.buildProject(inputs, sources, Nil, options, None, Scope.Main, logger, artifacts).orThrow

    expect(project.workspace == inputs.workspace)
  }
  test("skip passing release flag for java 8 for ScalaSimpleCompiler") {
    val javaHome        = jvm(8)
    val bloopJvmVersion = 17
    val (_, javacOptions, _) =
      testJvmReleaseIsSetCorrectly(javaHome, bloopJvmVersion = None)
    expect(!javacOptions.containsSlice(Seq("--release")))
  }
}
