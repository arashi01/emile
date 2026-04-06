import sbt.*

object BuildPlatformPlugin extends AutoPlugin {
  override def trigger: PluginTrigger = allRequirements
  val buildPlatform = settingKey[BuildPlatform]("The platform being built for (Linux, Windows, MacOS)")

  object autoImport {
    val buildPlatform = BuildPlatformPlugin.buildPlatform
    type BuildPlatform = BuildPlatformPlugin.BuildPlatform
    val BuildPlatform = BuildPlatformPlugin.BuildPlatform
  }

  sealed trait BuildPlatform extends Product with Serializable

  object BuildPlatform {
    case object Linux extends BuildPlatform
    case object Windows extends BuildPlatform
    case object MacOS extends BuildPlatform
  }

  def currentPlatform: BuildPlatform = {
    val osName = System.getProperty("os.name").toLowerCase
    if (osName.contains("win")) BuildPlatform.Windows
    else if (osName.contains("mac")) BuildPlatform.MacOS
    else if (osName.contains("nux")) BuildPlatform.Linux
    else throw new RuntimeException(s"Unsupported operating system: $osName")
  }

  override def buildSettings: Seq[Setting[?]] = Seq(
    buildPlatform := currentPlatform
  )

}
