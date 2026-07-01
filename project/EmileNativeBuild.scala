import sbt.*

import snx.sbt.SNXPlugin

object EmileNativeBuild extends AutoPlugin:

  override def trigger: PluginTrigger = noTrigger
  override def requires: Plugins = SNXPlugin

  object autoImport:
    val staticTestLink: SettingKey[Boolean] =
      settingKey[Boolean]("Whether to produce a fully-static test binary where the toolchain supports it.")

  override def projectSettings: Seq[Setting[?]] =
    import autoImport.*
    Seq(
      staticTestLink := sys.env.get("EMILE_STATIC_LINK").contains("true")
    )
