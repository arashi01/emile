/*
 * Copyright 2025 Ali Rashid.
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
package emile

import scala.scalanative.runtime.Platform
import scala.scalanative.unsafe.*

import munit.FunSuite

import emile.unsafe.LibUV

/**
 * Alignment checks to ensure our enum values match libuv's runtime enums.
 */
class HandleRequestAlignmentSuite extends FunSuite:

  test("HandleType matches uv_handle_get_type for core handles"):
    val result = for
      loop <- Loop.create
      async <- Async.init(loop)(() => ())
      timer <- Timer.init(loop)
      tcp <- Tcp.init(loop)
      poll <- Poll.init(loop, 0)
      // Verify native reported type maps to our HandleType
      _ = assertEquals(HandleType.fromLibuv(LibUV.uv_handle_get_type(async.ptrUnsafe)), HandleType.Async)
      _ = assertEquals(HandleType.fromLibuv(LibUV.uv_handle_get_type(timer.ptrUnsafe)), HandleType.Timer)
      _ = assertEquals(HandleType.fromLibuv(LibUV.uv_handle_get_type(tcp.ptrUnsafe)), HandleType.Tcp)
      _ = assertEquals(HandleType.fromLibuv(LibUV.uv_handle_get_type(poll.ptrUnsafe)), HandleType.Poll)
      _ = async.close
      _ = timer.close
      _ = tcp.close
      _ = poll.close
      _ <- loop.run(RunMode.Default)
      _ <- loop.close
    yield ()

    assert(result.isRight, s"Handle type alignment failed: $result")

  test("RequestType toLibuv/fromLibuv round-trip and sizes"):
    // Pure enum alignment: every toLibuv should round-trip via fromLibuv
    RequestType.values.foreach { rt =>
      assertEquals(RequestType.fromLibuv(rt.toLibuv), rt)
    }

    // Basic size sanity: libuv should return non-zero req sizes for known types
    val known = List(RequestType.Work, RequestType.Connect, RequestType.Write, RequestType.Shutdown)
    known.foreach { rt =>
      val size = LibUV.uv_req_size(rt.toLibuv)
      assert(size.toLong > 0L, s"uv_req_size returned $size for $rt")
    }

  test("RequestType matches uv_req_get_type for uv_queue_work"):
    val observed = NativeWorkStubs.workReqType()
    if observed < 0 && isWindows then
      // TODO: Provide Windows-specific uv_queue_work helper without pthreads and enable this assertion.
      assume(clue(false), s"uv_queue_work C helper unsupported on Windows (code $observed)")
    else
      assert(observed >= 0, s"uv_queue_work failed with code $observed")
      assertEquals(RequestType.fromLibuv(observed), RequestType.Work)
end HandleRequestAlignmentSuite

private def isWindows: Boolean =
  Platform.isWindows()

@extern
private object NativeWorkStubs:
  @name("emile_uv_work_req_type")
  def workReqType(): CInt = extern
