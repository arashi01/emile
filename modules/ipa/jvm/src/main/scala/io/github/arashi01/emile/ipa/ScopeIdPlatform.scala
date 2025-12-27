package io.github.arashi01.emile.ipa

import java.net.NetworkInterface

given platformScopeId: ScopeIdPlatform with
  def fromInterfaceName(name: String): Either[String, ScopeId] =
    val nic = NetworkInterface.getByName(name)
    if nic == null then Left(s"unknown network interface '$name'") // scalafix:ok DisableSyntax.null
    else
      val idx = nic.getIndex
      if idx <= 0 then Left(s"network interface '$name' has invalid index $idx")
      else Right(ScopeId(idx))
