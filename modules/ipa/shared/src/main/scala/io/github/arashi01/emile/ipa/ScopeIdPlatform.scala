package io.github.arashi01.emile.ipa

/** Platform hook for resolving IPv6 scope identifiers from interface names. */
trait ScopeIdPlatform:
  def fromInterfaceName(name: String): Either[String, ScopeId]

/** Exposes the platform-specific given in a companion so `import ScopeIdPlatform.given` works. */
object ScopeIdPlatform:
  export platformScopeId.given
