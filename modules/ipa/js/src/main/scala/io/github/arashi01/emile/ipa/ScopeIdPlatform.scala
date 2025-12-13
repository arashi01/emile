package io.github.arashi01.emile.ipa

given platformScopeId: ScopeIdPlatform with
  def fromInterfaceName(name: String): Either[String, ScopeId] =
    Left(s"scope identifiers by name are unsupported on JS target: '$name'")
