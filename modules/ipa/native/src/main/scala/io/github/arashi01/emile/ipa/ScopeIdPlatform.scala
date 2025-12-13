package io.github.arashi01.emile.ipa

import scala.scalanative.posix.net.`if`
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

given platformScopeId: ScopeIdPlatform with
  def fromInterfaceName(name: String): Either[String, ScopeId] =
    Zone.acquire { implicit z =>
      val cName = toCString(name)
      val idx = `if`.if_nametoindex(cName)
      if idx == 0.toUInt then Left(s"unknown network interface '$name'")
      else Right(ScopeId(idx.toInt))
    }
