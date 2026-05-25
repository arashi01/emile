import sbt.{Def, *}
import sbt.Keys.*
import sbt.librarymanagement.{CrossVersion, Disabled}

import scala.scalanative.build.NativeConfig
import scala.scalanative.sbtplugin.ScalaNativeCrossVersion
import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport.nativeConfig
import scala.sys.process.*

object EmileNativeBuild extends AutoPlugin:

  override def trigger: PluginTrigger = noTrigger
  override def requires: Plugins = ScalaNativePlugin

  object autoImport:
    val emileSystemLibUV = settingKey[Boolean](
      "Link emile against the system/distro libuv (true, -luv) or the vendored submodule (false)."
    )
    val emileStaticLink = settingKey[Boolean](
      "Produce a fully-static test binary (true, -static) or a dynamic one (false)."
    )
    val buildLibuv = taskKey[File]("Build the vendored libuv static library; returns its CMake build directory.")
    val libuvStaticLib = taskKey[File]("Absolute path to the built libuv static archive (libuv.a).")
  import autoImport.*

  enum Os:
    case Linux

  enum Arch:
    case X86_64, Aarch64

  enum Libc:
    case Glibc, Musl

  private val rawArch: String = sys.props.getOrElse("os.arch", "").toLowerCase

  val os: Os =
    if !sys.props.getOrElse("os.name", "").toLowerCase.contains("lin") then
      throw new RuntimeException("unsupported OS: " + sys.props("os.name"))
    else Os.Linux

  val arch: Arch = rawArch match
    case "x86_64" | "amd64" => Arch.X86_64
    case "aarch64" | "arm64" => Arch.Aarch64
    case _ => throw new RuntimeException("unsupported architecture: " + rawArch)

  val libc: Libc = os match
    case Os.Linux =>
      val a = arch match
        case Arch.X86_64 => "x86_64"
        case Arch.Aarch64 => "aarch64"
      if new java.io.File(s"/lib/ld-musl-$a.so.1").exists then Libc.Musl else Libc.Glibc

  /** A linkage-distinguishing host identifier, e.g. `linux-glibc-x86_64`. */
  val hostTag: String =
    val osPart = os match
      case Os.Linux => if libc == Libc.Musl then "linux-musl" else "linux-glibc"
    val archPart = arch match
      case Arch.X86_64 => "x86_64"
      case Arch.Aarch64 => "aarch64"
    s"$osPart-$archPart"

  override def globalSettings: Seq[Setting[?]] = Seq(
    emileSystemLibUV := sys.env.get("EMILE_SYSTEM_LIBUV").contains("true"),
    emileStaticLink := sys.env.get("EMILE_STATIC_LINK").contains("true")
  )

  override def buildSettings: Seq[Setting[?]] = Seq(
    ThisBuild / buildLibuv := Def.uncached(cmakeBuildLibuv.value),
    ThisBuild / libuvStaticLib := Def.uncached(resolveLibuvArchive.value)
  )

  override def projectSettings: Seq[Setting[?]] =
    nativeFoundationSettings ++ Seq(
      nativeConfig := Def.uncached {
        Def.taskDyn {
          if emileSystemLibUV.value then systemLinkConfig
          else vendoredLinkConfig
        }.value
      }
    )

  /** Settings common to every emile native module. */
  private def nativeFoundationSettings: Seq[Setting[?]] = Seq(
    moduleName := {
      val base = moduleName.value
      CrossVersion(ScalaNativeCrossVersion.binary, scalaVersion.value, scalaBinaryVersion.value)
        .fold(base)(_.apply(base))
    },
    projectID / crossVersion := Disabled(),
    libraryDependencySchemes += "org.scala-native" % "test-interface_native0.5_3" % "always"
  )

  // -no-pie defensively until the non-PIC relocation source is isolated -
  // see /home/coder/scala-native-pie-relocation-investigation/TASK.md
  private val NoPie: Seq[String] = Seq("-no-pie")

  /** Distro-libuv linkage via `-luv`. Static link pulls libuv's transitive dependencies on Linux
    * (pthread / dl / rt / m) into the command line explicitly; the dynamic loader brings them in
    * via DT_NEEDED when linking against the shared library.
    */
  private def systemLinkConfig: Def.Initialize[Task[NativeConfig]] = Def.task {
    val baseCfg = nativeConfig.value.withMultithreading(true)
    val opts =
      if emileStaticLink.value then Seq("-luv", "-lpthread", "-ldl", "-lrt", "-lm", "-static")
      else Seq("-luv") ++ NoPie
    baseCfg.withLinkingOptions(baseCfg.linkingOptions ++ opts)
  }

  /** Vendored-libuv linkage from the `vendor/libuv` submodule archive plus libuv's Linux deps. */
  private def vendoredLinkConfig: Def.Initialize[Task[NativeConfig]] = Def.task {
    val baseCfg = nativeConfig.value.withMultithreading(true)
    val archive = (ThisBuild / libuvStaticLib).value.getAbsolutePath
    val deps = Seq("-lpthread", "-ldl", "-lrt", "-lm")
    val tail = if emileStaticLink.value then Seq("-static") else NoPie
    baseCfg.withLinkingOptions(baseCfg.linkingOptions ++ (archive +: deps) ++ tail)
  }

  private def cmakeBuildLibuv: Def.Initialize[Task[File]] = Def.task {
    val log = streams.value.log
    val root = (ThisBuild / baseDirectory).value
    val src = root / "vendor" / "libuv"
    val out = root / "target" / "libuv" / hostTag

    if !(src / "CMakeLists.txt").exists then
      sys.error("vendor/libuv submodule is not initialised - run: git submodule update --init vendor/libuv")

    val stamp = out / ".emile-libuv-stamp"
    val expected = libuvStampContent(src)
    val current = if stamp.isFile then IO.read(stamp).trim else ""

    if current != expected then
      log.info(s"Building vendored libuv [$hostTag] from source ...")
      IO.createDirectory(out)
      val configure = Seq(
        "cmake",
        "-S",
        src.getAbsolutePath,
        "-B",
        out.getAbsolutePath,
        "-DLIBUV_BUILD_SHARED=OFF",
        "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
        "-DCMAKE_BUILD_TYPE=Release"
      )
      if Process(configure).!(log) != 0 then sys.error("libuv CMake configuration failed")
      if Process(Seq("cmake", "--build", out.getAbsolutePath, "--config", "Release", "--parallel")).!(log) != 0 then
        sys.error("libuv CMake build failed")
      IO.write(stamp, expected)
    else log.debug(s"vendored libuv [$hostTag] is up to date")
    end if

    out
  }

  private def resolveLibuvArchive: Def.Initialize[Task[File]] = Def.task {
    val out = (ThisBuild / buildLibuv).value
    out.allPaths.get().toSeq.filter(f => f.isFile && f.getName == "libuv.a") match
      case Seq(archive) => archive
      case Seq() => sys.error(s"no libuv.a produced under ${out.getAbsolutePath}")
      case many => sys.error(s"multiple libuv.a archives under ${out.getAbsolutePath}: ${many.mkString(", ")}")
  }

  /** Submodule commit plus host tag - the vendored-libuv rebuild trigger. */
  private def libuvStampContent(src: File): String =
    val sha = Process(Seq("git", "-C", src.getAbsolutePath, "rev-parse", "HEAD")).!!.trim
    s"libuv=$sha;host=$hostTag"

end EmileNativeBuild
