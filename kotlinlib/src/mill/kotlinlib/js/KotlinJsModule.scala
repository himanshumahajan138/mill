package mill.kotlinlib.js

import mainargs.arg
import mill.api.{PathRef, Result}
import mill.define.{Command, Task}
import mill.kotlinlib.worker.api.{KotlinWorker, KotlinWorkerTarget}
import mill.kotlinlib.{Dep, DepSyntax, KotlinModule}
import mill.scalalib.Lib
import mill.scalalib.api.CompilationResult
import mill.testrunner.TestResult
import mill.util.Jvm
import mill.{Agg, Args, T}
import sbt.testing.Status
import upickle.default.{macroRW, ReadWriter => RW}

import java.io.{File, FileNotFoundException}
import java.util.zip.ZipFile
import scala.xml.XML

/**
 * This module is very experimental. Don't use it, it is still under the development, APIs can change.
 */
trait KotlinJsModule extends KotlinModule { outer =>

  // region Kotlin/JS configuration

  /** The kind of JS module generated by the compiler */
  def moduleKind: T[ModuleKind] = ModuleKind.PlainModule

  /** Call main function upon execution. */
  def callMain: T[Boolean] = true

  /** Binary type (if any) to produce. If [[BinaryKind.Executable]] is selected, then .js file(s) will be produced. */
  def kotlinJsBinaryKind: T[Option[BinaryKind]] = Some(BinaryKind.Executable)

  /** Whether to emit a source map. */
  def kotlinJsSourceMap: T[Boolean] = true

  /** Whether to embed sources into source map. */
  def kotlinJsSourceMapEmbedSources: T[SourceMapEmbedSourcesKind] = SourceMapEmbedSourcesKind.Never

  /** ES target to use. List of the supported ones depends on the Kotlin version. If not provided, default is used. */
  def kotlinJsESTarget: T[Option[String]] = None

  /**
   * Add variable and function names that you declared in Kotlin code into the source map. See
   *  [[https://kotlinlang.org/docs/compiler-reference.html#source-map-names-policy-simple-names-fully-qualified-names-no Kotlin docs]] for more details
   */
  def kotlinJsSourceMapNamesPolicy: T[SourceMapNamesPolicy] = SourceMapNamesPolicy.No

  /** Split generated .js per-module. Effective only if [[BinaryKind.Executable]] is selected. */
  def splitPerModule: T[Boolean] = true

  /** Run target for the executable (if [[BinaryKind.Executable]] is set). */
  def kotlinJsRunTarget: T[Option[RunTarget]] = None

  // endregion

  // region parent overrides

  override def allSourceFiles: T[Seq[PathRef]] = Task {
    Lib.findSourceFiles(allSources(), Seq("kt")).map(PathRef(_))
  }

  override def mandatoryIvyDeps: T[Agg[Dep]] = Task {
    Agg(
      ivy"org.jetbrains.kotlin:kotlin-stdlib-js:${kotlinVersion()}"
    )
  }

  override def transitiveCompileClasspath: T[Agg[PathRef]] = Task {
    Task.traverse(transitiveModuleCompileModuleDeps)(m =>
      Task.Anon {
        val transitiveModuleArtifactPath =
          if (m.isInstanceOf[KotlinJsModule] && m != friendModule.orNull) {
            m.asInstanceOf[KotlinJsModule].klib()
          } else m.compile().classes
        m.localCompileClasspath() ++ Agg(transitiveModuleArtifactPath)
      }
    )().flatten
  }

  /**
   * Compiles all the sources to the IR representation.
   */
  override def compile: T[CompilationResult] = Task {
    kotlinJsCompile(
      outputMode = OutputMode.KlibDir,
      irClasspath = None,
      allKotlinSourceFiles = allKotlinSourceFiles(),
      librariesClasspath = compileClasspath(),
      callMain = callMain(),
      moduleKind = moduleKind(),
      produceSourceMaps = kotlinJsSourceMap(),
      sourceMapEmbedSourcesKind = kotlinJsSourceMapEmbedSources(),
      sourceMapNamesPolicy = kotlinJsSourceMapNamesPolicy(),
      splitPerModule = splitPerModule(),
      esTarget = kotlinJsESTarget(),
      kotlinVersion = kotlinVersion(),
      destinationRoot = Task.dest,
      artifactId = artifactId(),
      explicitApi = kotlinExplicitApi(),
      extraKotlinArgs = kotlincOptions(),
      worker = kotlinWorkerTask()
    )
  }

  override def runLocal(args: Task[Args] = Task.Anon(Args())): Command[Unit] =
    Task.Command { run(args)() }

  override def run(args: Task[Args] = Task.Anon(Args())): Command[Unit] = Task.Command {
    runJsBinary(
      args = args(),
      binaryKind = kotlinJsBinaryKind(),
      moduleKind = moduleKind(),
      binaryDir = linkBinary().classes.path,
      runTarget = kotlinJsRunTarget(),
      artifactId = artifactId(),
      envArgs = Task.env,
      workingDir = Task.dest
    ).map(_ => ()).getOrThrow
  }

  override def runMainLocal(
      @arg(positional = true) mainClass: String,
      args: String*
  ): Command[Unit] = Task.Command[Unit] {
    mill.api.Result.Failure("runMain is not supported in Kotlin/JS.")
  }

  override def runMain(@arg(positional = true) mainClass: String, args: String*): Command[Unit] =
    Task.Command[Unit] {
      mill.api.Result.Failure("runMain is not supported in Kotlin/JS.")
    }

  protected[js] def friendModule: Option[KotlinJsModule] = None

  protected[js] def runJsBinary(
      args: Args = Args(),
      binaryKind: Option[BinaryKind],
      moduleKind: ModuleKind,
      binaryDir: os.Path,
      runTarget: Option[RunTarget],
      artifactId: String,
      envArgs: Map[String, String] = Map.empty[String, String],
      workingDir: os.Path
  )(implicit ctx: mill.api.Ctx): Result[Int] = {
    if (binaryKind.isEmpty || binaryKind.get != BinaryKind.Executable) {
      return Result.Failure("Run action is only allowed for the executable binary")
    }

    if (
      moduleKind == ModuleKind.NoModule &&
      binaryDir.toIO.listFiles().count(_.getName.endsWith(".js")) > 1
    ) {
      Task.log.info("No module type is selected for the executable, but multiple .js files found in the output folder." +
        " This will probably lead to the dependency resolution failure.")
    }

    runTarget match {
      case Some(RunTarget.Node) =>
        val binaryPath = (binaryDir / s"$artifactId.${moduleKind.extension}")
          .toIO.getAbsolutePath
        val processResult = os.call(
          cmd = Seq("node") ++ args.value ++ Seq(binaryPath),
          env = envArgs,
          cwd = workingDir,
          stdin = os.Inherit,
          stdout = os.Inherit,
          check = false
        )
        if (processResult.exitCode == 0) Result.Success(processResult.exitCode)
        else Result.Failure(
          "Interactive Subprocess Failed (exit code " + processResult.exitCode + ")",
          Some(processResult.exitCode)
        )
      case None =>
        Result.Failure("Executable binary should have a run target selected.")
    }
  }

  /**
   * The actual Kotlin compile task (used by [[compile]] and [[kotlincHelp]]).
   */
  protected override def kotlinCompileTask(
      extraKotlinArgs: Seq[String] = Seq.empty[String]
  ): Task[CompilationResult] = Task.Anon {
    kotlinJsCompile(
      outputMode = OutputMode.KlibDir,
      allKotlinSourceFiles = allKotlinSourceFiles(),
      irClasspath = None,
      librariesClasspath = compileClasspath(),
      callMain = callMain(),
      moduleKind = moduleKind(),
      produceSourceMaps = kotlinJsSourceMap(),
      sourceMapEmbedSourcesKind = kotlinJsSourceMapEmbedSources(),
      sourceMapNamesPolicy = kotlinJsSourceMapNamesPolicy(),
      splitPerModule = splitPerModule(),
      esTarget = kotlinJsESTarget(),
      kotlinVersion = kotlinVersion(),
      destinationRoot = Task.dest,
      artifactId = artifactId(),
      explicitApi = kotlinExplicitApi(),
      extraKotlinArgs = kotlincOptions() ++ extraKotlinArgs,
      worker = kotlinWorkerTask()
    )
  }

  /**
   * Creates final executable.
   */
  def linkBinary: T[CompilationResult] = Task {
    kotlinJsCompile(
      outputMode = binaryKindToOutputMode(kotlinJsBinaryKind()),
      // classpath with classes of this module's code
      irClasspath = Some(compile().classes),
      allKotlinSourceFiles = Seq.empty,
      // classpath of libraries to be used to run this module's code
      librariesClasspath = upstreamAssemblyClasspath(),
      callMain = callMain(),
      moduleKind = moduleKind(),
      produceSourceMaps = kotlinJsSourceMap(),
      sourceMapEmbedSourcesKind = kotlinJsSourceMapEmbedSources(),
      sourceMapNamesPolicy = kotlinJsSourceMapNamesPolicy(),
      splitPerModule = splitPerModule(),
      esTarget = kotlinJsESTarget(),
      kotlinVersion = kotlinVersion(),
      destinationRoot = Task.dest,
      artifactId = artifactId(),
      explicitApi = kotlinExplicitApi(),
      extraKotlinArgs = kotlincOptions(),
      worker = kotlinWorkerTask()
    )
  }

  /**
   * A klib containing only this module's resources and compiled IR files,
   * without those from upstream modules and dependencies
   */
  def klib: T[PathRef] = Task {
    val outputPath = Task.dest / s"${artifactId()}.klib"
    Jvm.createJar(
      outputPath,
      Agg(compile().classes.path),
      mill.util.JarManifest.MillDefault,
      fileFilter = (_, _) => true
    )
    PathRef(outputPath)
  }

  // endregion

  // region private

  protected override def dokkaAnalysisPlatform = "js"
  protected override def dokkaSourceSetDisplayName = "js"

  private[kotlinlib] def kotlinJsCompile(
      outputMode: OutputMode,
      allKotlinSourceFiles: Seq[PathRef],
      irClasspath: Option[PathRef],
      librariesClasspath: Agg[PathRef],
      callMain: Boolean,
      moduleKind: ModuleKind,
      produceSourceMaps: Boolean,
      sourceMapEmbedSourcesKind: SourceMapEmbedSourcesKind,
      sourceMapNamesPolicy: SourceMapNamesPolicy,
      splitPerModule: Boolean,
      esTarget: Option[String],
      kotlinVersion: String,
      destinationRoot: os.Path,
      artifactId: String,
      explicitApi: Boolean,
      extraKotlinArgs: Seq[String],
      worker: KotlinWorker
  )(implicit ctx: mill.api.Ctx): Result[CompilationResult] = {
    val versionAllowed = kotlinVersion.split("\\.").map(_.toInt) match {
      case Array(1, 8, z) => z >= 20
      case Array(1, y, _) => y >= 9
      case _ => true
    }
    if (!versionAllowed) {
      // have to put this restriction, because for older versions some compiler options either didn't exist or
      // had different names. It is possible to go to the lower version supported with a certain effort.
      ctx.log.error("Minimum supported Kotlin version for JS target is 1.8.20.")
      return Result.Aborted
    }

    // compiler options references:
    // * https://kotlinlang.org/docs/compiler-reference.html#kotlin-js-compiler-options
    // * https://github.com/JetBrains/kotlin/blob/v1.9.25/compiler/cli/cli-common/src/org/jetbrains/kotlin/cli/common/arguments/K2JSCompilerArguments.kt

    val inputFiles = irClasspath match {
      case Some(x) => Seq(s"-Xinclude=${x.path.toIO.getAbsolutePath}")
      case None => allKotlinSourceFiles.map(_.path.toIO.getAbsolutePath)
    }

    val librariesCp = librariesClasspath.map(_.path)
      .filter(os.exists)
      .filter(isKotlinJsLibrary)

    val innerCompilerArgs = Seq.newBuilder[String]
    // classpath
    innerCompilerArgs ++= Seq("-libraries", librariesCp.iterator.mkString(File.pathSeparator))
    innerCompilerArgs ++= Seq("-main", if (callMain) "call" else "noCall")
    innerCompilerArgs += "-meta-info"
    if (moduleKind != ModuleKind.NoModule) {
      innerCompilerArgs ++= Seq(
        "-module-kind",
        moduleKind match {
          case ModuleKind.AMDModule => "amd"
          case ModuleKind.UMDModule => "umd"
          case ModuleKind.PlainModule => "plain"
          case ModuleKind.ESModule => "es"
          case ModuleKind.CommonJSModule => "commonjs"
          case ModuleKind.NoModule => ???
        }
      )
    }
    innerCompilerArgs ++= Seq("-ir-output-name", s"$artifactId")
    if (produceSourceMaps) {
      innerCompilerArgs += "-source-map"
      innerCompilerArgs ++= Seq(
        "-source-map-embed-sources",
        sourceMapEmbedSourcesKind match {
          case SourceMapEmbedSourcesKind.Always => "always"
          case SourceMapEmbedSourcesKind.Never => "never"
          case SourceMapEmbedSourcesKind.Inlining => "inlining"
        }
      )
      innerCompilerArgs ++= Seq(
        "-source-map-names-policy",
        sourceMapNamesPolicy match {
          case SourceMapNamesPolicy.No => "no"
          case SourceMapNamesPolicy.SimpleNames => "simple-names"
          case SourceMapNamesPolicy.FullyQualifiedNames => "fully-qualified-names"
        }
      )
    }
    innerCompilerArgs += "-Xir-only"
    if (splitPerModule) {
      innerCompilerArgs += s"-Xir-per-module"
      // should be unique among all the modules loaded in the consumer classpath
      innerCompilerArgs += s"-Xir-per-module-output-name=$artifactId"
    }
    // apply multi-platform support (expect/actual)
    // TODO if there is penalty for activating it in the compiler, put it behind configuration flag
    innerCompilerArgs += "-Xmulti-platform"
    val outputArgs = outputMode match {
      case OutputMode.KlibFile =>
        Seq(
          "-Xir-produce-klib-file",
          "-ir-output-dir",
          (destinationRoot / "libs").toIO.getAbsolutePath
        )
      case OutputMode.KlibDir =>
        Seq(
          "-Xir-produce-klib-dir",
          "-ir-output-dir",
          (destinationRoot / "classes").toIO.getAbsolutePath
        )
      case OutputMode.Js =>
        Seq(
          "-Xir-produce-js",
          "-ir-output-dir",
          (destinationRoot / "binaries").toIO.getAbsolutePath
        )
    }

    innerCompilerArgs ++= outputArgs
    // should be unique among all the modules loaded in the consumer classpath
    innerCompilerArgs += s"-Xir-module-name=$artifactId"
    innerCompilerArgs ++= (esTarget match {
      case Some(x) => Seq("-target", x)
      case None => Seq.empty
    })
    if (explicitApi) {
      innerCompilerArgs ++= Seq("-Xexplicit-api=strict")
    }

    val compilerArgs: Seq[String] = Seq(
      innerCompilerArgs.result(),
      extraKotlinArgs,
      // parameters
      inputFiles
    ).flatten

    val compileDestination = os.Path(outputArgs.last)
    if (irClasspath.isEmpty) {
      Task.log.info(
        s"Compiling ${allKotlinSourceFiles.size} Kotlin sources to $compileDestination ..."
      )
    } else {
      Task.log.info(s"Linking IR to $compileDestination")
    }
    val workerResult = worker.compile(KotlinWorkerTarget.Js, compilerArgs)

    val analysisFile = Task.dest / "kotlin.analysis.dummy"
    if (!os.exists(analysisFile)) {
      os.write(target = analysisFile, data = "", createFolders = true)
    }

    val artifactLocation = outputMode match {
      case OutputMode.KlibFile => compileDestination / s"$artifactId.klib"
      case OutputMode.KlibDir => compileDestination
      case OutputMode.Js => compileDestination
    }

    workerResult match {
      case Result.Success(_) =>
        CompilationResult(analysisFile, PathRef(artifactLocation))
      case Result.Failure(reason, _) =>
        Result.Failure(reason, Some(CompilationResult(analysisFile, PathRef(artifactLocation))))
      case e: Result.Exception => e
      case Result.Aborted => Result.Aborted
      case Result.Skipped => Result.Skipped
    }
  }

  private def binaryKindToOutputMode(binaryKind: Option[BinaryKind]): OutputMode =
    binaryKind match {
      // still produce IR classes, but they won't be yet linked
      case None => OutputMode.KlibDir
      case Some(BinaryKind.Library) => OutputMode.KlibFile
      case Some(BinaryKind.Executable) => OutputMode.Js
    }

  // **NOTE**: This logic may (and probably is) be incomplete
  private def isKotlinJsLibrary(path: os.Path)(implicit ctx: mill.api.Ctx): Boolean = {
    if (os.isDir(path)) {
      true
    } else if (path.ext == "klib") {
      true
    } else if (path.ext == "jar") {
      try {
        // TODO cache these lookups. May be a big performance penalty.
        val zipFile = new ZipFile(path.toIO)
        zipFile.stream()
          .anyMatch(entry => entry.getName.endsWith(".meta.js") || entry.getName.endsWith(".kjsm"))
      } catch {
        case e: Throwable =>
          Task.log.error(s"Couldn't open ${path.toIO.getAbsolutePath} as archive.\n${e.toString}")
          false
      }
    } else {
      Task.log.debug(s"${path.toIO.getAbsolutePath} is not a Kotlin/JS library, ignoring it.")
      false
    }
  }

  override def artifactId: T[String] = Task {
    val name = super.artifactId()
    if (name.isEmpty) {
      "root"
    } else {
      name
    }
  }

  // endregion

  // region Tests module

  /**
   * Generic trait to run tests for Kotlin/JS which doesn't specify test
   * framework. For the particular implementation see [[KotlinTestPackageTests]] or [[KotestTests]].
   */
  trait KotlinJsTests extends KotlinJsModule with KotlinTests {

    private val defaultXmlReportName = "test-report.xml"

    /**
     * Test timeout in milliseconds. Default is 2000.
     */
    def testTimeout: T[Long] = Task { 2000L }

    // region private

    // TODO may be optimized if there is a single folder for all modules
    // but may be problematic if modules use different NPM packages versions
    private def nodeModulesDir = Task(persistent = true) {
      os.call(
        cmd = Seq("npm", "install", "mocha@10.2.0", "source-map-support@0.5.21"),
        env = Task.env,
        cwd = Task.dest,
        stdin = os.Inherit,
        stdout = os.Inherit
      )
      PathRef(Task.dest)
    }

    // NB: for the packages below it is important to use specific version
    // otherwise with random versions there is a possibility to have conflict
    // between the versions of the shared transitive deps
    private def mochaModule = Task {
      PathRef(nodeModulesDir().path / "node_modules/mocha/bin/mocha.js")
    }

    private def sourceMapSupportModule = Task {
      PathRef(nodeModulesDir().path / "node_modules/source-map-support/register.js")
    }

    // endregion

    override def kotlincOptions: T[Seq[String]] = Task {
      super.kotlincOptions().map { item =>
        if (item.startsWith("-Xfriend-paths=")) {
          // JVM -> JS option name
          item.replace("-Xfriend-paths=", "-Xfriend-modules=")
        } else {
          item
        }
      }
    }

    override def testFramework = ""

    override def kotlinJsRunTarget: T[Option[RunTarget]] = outer.kotlinJsRunTarget()

    override def moduleKind: T[ModuleKind] = ModuleKind.PlainModule

    override def splitPerModule = false

    override def testLocal(args: String*): Command[(String, Seq[TestResult])] =
      Task.Command {
        this.test(args*)()
      }

    override protected[js] def friendModule: Option[KotlinJsModule] = Some(outer)

    override protected def testTask(
        args: Task[Seq[String]],
        globSelectors: Task[Seq[String]]
    ): Task[(String, Seq[TestResult])] = Task.Anon {
      val runTarget = kotlinJsRunTarget()
      if (runTarget.isEmpty) {
        throw new IllegalStateException(
          "Cannot run Kotlin/JS tests, because run target is not specified."
        )
      }
      runJsBinary(
        // TODO add runner to be able to use test selector
        args = Args(args() ++ Seq(
          // TODO this is valid only for the NodeJS target. Once browser support is
          //  added, need to have different argument handling
          "--require",
          sourceMapSupportModule().path.toString(),
          mochaModule().path.toString(),
          "--timeout",
          testTimeout().toString,
          "--reporter",
          "xunit",
          "--reporter-option",
          s"output=${testReportXml().getOrElse(defaultXmlReportName)}"
        )),
        binaryKind = Some(BinaryKind.Executable),
        moduleKind = moduleKind(),
        binaryDir = linkBinary().classes.path,
        runTarget = runTarget,
        artifactId = artifactId(),
        envArgs = Task.env,
        workingDir = Task.dest
      )

      // we don't care about the result returned above (because node will return exit code = 1 when tests fail), what
      // matters is if test results file exists
      val xmlReportName = testReportXml().getOrElse(defaultXmlReportName)
      val xmlReportPath = Task.dest / xmlReportName
      val testResults = parseTestResults(xmlReportPath)
      val totalCount = testResults.length
      val passedCount = testResults.count(_.status == Status.Success.name())
      val failedCount = testResults.count(_.status == Status.Failure.name())
      val skippedCount = testResults.count(_.status == Status.Skipped.name())
      val doneMessage =
        s"""
           |Tests: $totalCount, Passed: $passedCount, Failed: $failedCount, Skipped: $skippedCount
           |
           |Full report is available at $xmlReportPath
           |""".stripMargin

      if (failedCount != 0) {
        val failedTests = testResults
          .filter(_.status == Status.Failure.name())
          .map(result =>
            if (result.exceptionName.isEmpty && result.exceptionMsg.isEmpty) {
              s"${result.fullyQualifiedName} - ${result.selector}"
            } else {
              s"${result.fullyQualifiedName} - ${result.selector}: ${result.exceptionName.getOrElse("<>")}:" +
                s" ${result.exceptionMsg.getOrElse("<>")}"
            }
          )
        val failureMessage =
          s"""
             |Tests failed:
             |
             |${failedTests.mkString("\n")}
             |
             |""".stripMargin
        Result.Failure(failureMessage, Some((doneMessage, testResults)))
      } else {
        Result.Success((doneMessage, testResults))
      }
    }

    private def parseTestResults(path: os.Path): Seq[TestResult] = {
      if (!os.exists(path)) {
        throw new FileNotFoundException(s"Test results file $path wasn't found")
      }
      val xml = XML.loadFile(path.toIO)
      (xml \ "testcase")
        .map { node =>
          val (testStatus, exceptionName, exceptionMessage, exceptionTrace) =
            if (node.child.exists(_.label == "failure")) {
              val content = (node \ "failure")
                .head
                .child
                .filter(_.isAtom)
                .text
              val lines = content.split("\n")
              val exceptionMessage = lines.head
              val exceptionType = lines(1).splitAt(lines(1).indexOf(":"))._1
              val trace = parseTrace(lines.drop(2))
              (Status.Failure, Some(exceptionType), Some(exceptionMessage), Some(trace))
            } else if (node.child.exists(_.label == "skipped")) {
              (Status.Skipped, None, None, None)
            } else {
              (Status.Success, None, None, None)
            }

          TestResult(
            fullyQualifiedName = node \@ "classname",
            selector = node \@ "name",
            // probably in milliseconds?
            duration = ((node \@ "time").toDouble * 1000).toLong,
            status = testStatus.name(),
            exceptionName = exceptionName,
            exceptionMsg = exceptionMessage,
            exceptionTrace = exceptionTrace
          )
        }
    }

    private def parseTrace(trace: Seq[String]): Seq[StackTraceElement] = {
      trace.map { line =>
        // there are some lines with methods like this: $interceptCOROUTINE$97.l [as test_1], no idea what is this.
        val strippedLine = line.trim.stripPrefix("at ")
        val (symbol, location) = strippedLine.splitAt(strippedLine.lastIndexOf("("))
        // symbol can be like that HelloTests$_init_$lambda$slambda_wolooq_1.protoOf.doResume_5yljmg_k$
        // assume that everything past first dot is a method name, and everything before - some synthetic class name
        // this may be completely wrong though, but at least location will be right
        val (declaringClass, method) = if (symbol.contains(".")) {
          symbol.splitAt(symbol.indexOf("."))
        } else {
          ("", symbol)
        }
        // can be what we expect in case if line is pure-JVM:
        // src/internal/JSDispatcher.kt:127:25
        // but can also be something like:
        // node:internal/process/task_queues:77:11
        // drop closing ), then after split drop position on the line
        val locationElements = location.dropRight(1).split(":").dropRight(1)
        if (locationElements.length >= 2) {
          new StackTraceElement(
            declaringClass,
            method,
            locationElements(locationElements.length - 2),
            locationElements.last.toInt
          )
        } else {
          new StackTraceElement(declaringClass, method, "<unknown>", 0)
        }
      }
    }
  }

  /**
   * Run tests for Kotlin/JS target using `kotlin.test` package.
   */
  trait KotlinTestPackageTests extends KotlinJsTests {
    override def ivyDeps = Agg(
      ivy"org.jetbrains.kotlin:kotlin-test-js:${kotlinVersion()}"
    )
  }

  /**
   * Run tests for Kotlin/JS target using Kotest framework.
   */
  trait KotestTests extends KotlinJsTests {

    def kotestVersion: T[String] = "5.9.1"

    private def kotestProcessor = Task {
      defaultResolver().resolveDeps(
        Agg(
          ivy"io.kotest:kotest-framework-multiplatform-plugin-embeddable-compiler-jvm:${kotestVersion()}"
        )
      ).head
    }

    override def kotlincOptions = super.kotlincOptions() ++ Seq(
      s"-Xplugin=${kotestProcessor().path}"
    )

    override def ivyDeps = Agg(
      ivy"io.kotest:kotest-framework-engine-js:${kotestVersion()}",
      ivy"io.kotest:kotest-assertions-core-js:${kotestVersion()}"
    )
  }

  // endregion
}

sealed trait ModuleKind { def extension: String }

object ModuleKind {
  case object NoModule extends ModuleKind { val extension = "js" }
  implicit val rwNoModule: RW[NoModule.type] = macroRW
  case object UMDModule extends ModuleKind { val extension = "js" }
  implicit val rwUMDModule: RW[UMDModule.type] = macroRW
  case object CommonJSModule extends ModuleKind { val extension = "js" }
  implicit val rwCommonJSModule: RW[CommonJSModule.type] = macroRW
  case object AMDModule extends ModuleKind { val extension = "js" }
  implicit val rwAMDModule: RW[AMDModule.type] = macroRW
  case object ESModule extends ModuleKind { val extension = "mjs" }
  implicit val rwESModule: RW[ESModule.type] = macroRW
  case object PlainModule extends ModuleKind { val extension = "js" }
  implicit val rwPlainModule: RW[PlainModule.type] = macroRW
}

sealed trait SourceMapEmbedSourcesKind
object SourceMapEmbedSourcesKind {
  case object Always extends SourceMapEmbedSourcesKind
  implicit val rwAlways: RW[Always.type] = macroRW
  case object Never extends SourceMapEmbedSourcesKind
  implicit val rwNever: RW[Never.type] = macroRW
  case object Inlining extends SourceMapEmbedSourcesKind
  implicit val rwInlining: RW[Inlining.type] = macroRW
}

sealed trait SourceMapNamesPolicy
object SourceMapNamesPolicy {
  case object SimpleNames extends SourceMapNamesPolicy
  implicit val rwSimpleNames: RW[SimpleNames.type] = macroRW
  case object FullyQualifiedNames extends SourceMapNamesPolicy
  implicit val rwFullyQualifiedNames: RW[FullyQualifiedNames.type] = macroRW
  case object No extends SourceMapNamesPolicy
  implicit val rwNo: RW[No.type] = macroRW
}

sealed trait BinaryKind
object BinaryKind {
  case object Library extends BinaryKind
  implicit val rwLibrary: RW[Library.type] = macroRW
  case object Executable extends BinaryKind
  implicit val rwExecutable: RW[Executable.type] = macroRW
  implicit val rw: RW[BinaryKind] = macroRW
}

sealed trait RunTarget
object RunTarget {
  // TODO rely on the node version installed in the env or fetch a specific one?
  case object Node extends RunTarget
  implicit val rwNode: RW[Node.type] = macroRW
  implicit val rw: RW[RunTarget] = macroRW
}

private[kotlinlib] sealed trait OutputMode
private[kotlinlib] object OutputMode {
  case object Js extends OutputMode
  case object KlibDir extends OutputMode
  case object KlibFile extends OutputMode
}
