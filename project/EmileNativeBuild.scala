import sbt.*

import snx.sbt.SNXPlugin

object EmileNativeBuild extends AutoPlugin:

  override def trigger: PluginTrigger = noTrigger
  override def requires: Plugins = SNXPlugin

  object autoImport:
    val useSystemLibUV: SettingKey[Boolean] = settingKey[Boolean]("Whether to link against the system libuv (`-luv`).")
    val staticTestLink: SettingKey[Boolean] =
      settingKey[Boolean]("Whether to produce a fully-static test binary where the toolchain supports it.")

  override def projectSettings: Seq[Setting[?]] =
    import autoImport.*
    Seq(
      useSystemLibUV := sys.env.get("EMILE_SYSTEM_LIBUV").contains("true"),
      staticTestLink := sys.env.get("EMILE_STATIC_LINK").contains("true")
    )

end EmileNativeBuild
