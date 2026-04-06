/*
 * Copyright 2025, 2026 Ali Rashid.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package emile.ipa

import scala.scalanative.posix.arpa.inet.*
import scala.scalanative.posix.net.`if`
import scala.scalanative.posix.netinet.in.*
import scala.scalanative.posix.netinet.inOps.*
import scala.scalanative.posix.sys.socket.*
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import munit.FunSuite

/** POSIX-specific native platform tests.
  *
  * Tests requiring POSIX APIs (if_nameindex) that are unavailable on Windows.
  */
class PosixNativePlatformSpec extends FunSuite:
// scalafix:off

  private def firstInterface(): (String, Int) =
    Zone.acquire { implicit z =>
      val names = `if`.if_nameindex()
      if names == null then fail("if_nameindex returned null")
      try
        var cursor = names
        var chosen: Option[(String, Int)] = None
        while cursor._1 != 0.toUInt && cursor._2 != null && chosen.isEmpty do
          val name = fromCString(cursor._2)
          val idx = cursor._1.toInt
          if idx > 0 && name.nonEmpty then chosen = Some((name, idx))
          cursor = cursor + 1
        chosen.getOrElse(fail("No network interfaces available for scope ID test"))
      finally `if`.if_freenameindex(names)
    }

  test("uv_ip6_addr handles scope-aware addresses and roundtrips scopeId/flowInfo"):
    val (ifaceName, ifaceIndex) = firstInterface()
    Zone.acquire { implicit z =>
      val sin6 = alloc[sockaddr_in6]()
      val addressWithScope = s"fe80::1%$ifaceName"
      val res = LibuvNet.uv_ip6_addr(toCString(addressWithScope), 5353, sin6)
      assertEquals(res.toInt, 0, clue = s"uv_ip6_addr failed for $addressWithScope")

      // Override flowinfo to a non-default value
      sin6.sin6_flowinfo = htonl(0x1234.toUInt)

      val parsed = fromSockAddr(sin6.asInstanceOf[Ptr[sockaddr]])
      assert(parsed.isRight)
      parsed.foreach {
        case SocketAddress.V6(_, port, fi, sid) =>
          assertEquals(port.value, 5353)
          assertEquals(fi.value, 0x1234)
          assertEquals(sid.value, ifaceIndex)
        case other => fail(s"Expected V6, got $other")
      }

      val buf = alloc[Byte](64)
      val nameRes = LibuvNet.uv_ip6_name(sin6, buf, 64.toUSize)
      assertEquals(nameRes.toInt, 0, clue = "uv_ip6_name failed")
      val named = fromCString(buf)
      assert(named.startsWith("fe80::1"), clue = s"expected address, got $named")
      assertEquals(sin6.sin6_scope_id.toInt, ifaceIndex, clue = "scope id preserved after uv_ip6_name")
    }

end PosixNativePlatformSpec
