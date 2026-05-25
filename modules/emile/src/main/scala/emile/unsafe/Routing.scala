/*
 * Copyright 2025, 2026 Ali Rashid
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
package emile.unsafe

import scala.scalanative.libc.stdlib
import scala.scalanative.runtime.Intrinsics
import scala.scalanative.runtime.fromRawPtr
import scala.scalanative.runtime.toRawPtr
import scala.scalanative.unsafe.Ptr
import scala.util.control.NonFatal

import cats.effect.IO

/** Worker-affinity routing: runs a thunk on the libuv loop thread that owns a [[LibuvPoller]], with
  * a cancellation finaliser on the cross-thread path.
  */
private[emile] object Routing:

  /** Run `thunk` on `poller`'s loop thread. The fast path - caller already on the owner thread - is
    * a direct `IO`; the slow path submits the work across threads and yields a finaliser that
    * removes the still-queued runnable.
    *
    * The fast-vs-slow choice sits inside `IO.defer`, so it is decided by the thread that *runs* the
    * result, not the one that built it. This is load-bearing: a cancellation finaliser is built
    * while the `IO.async` body runs on the owner, yet cats-effect runs it on whichever worker
    * processes the cancellation - re-checking ownership at run time routes it back.
    */
  def onOwner[A](poller: LibuvPoller)(thunk: => A): IO[A] =
    IO.defer:
      if poller.isOwnerThread then IO(thunk)
      else
        IO.async[A]: cb =>
          IO.delay:
            val runnable: Runnable = () =>
              // Direct try/catch, not Try(thunk).toEither - avoids the intermediate Try
              // allocation on the cross-thread path; NonFatal matches Try's catch set exactly.
              val outcome: Either[Throwable, A] =
                try Right(thunk)
                catch case NonFatal(t) => Left(t)
              cb(outcome)
            if poller.submit(runnable) then Some(IO.delay { poller.remove(runnable): Unit; () })
            else
              cb(Left(new IllegalStateException("emile: poller closed")))
              None

  /** Close `handle` on `poller`'s loop thread, completing once libuv's close callback has fired and
    * freed the handle's C memory - the canonical release step for a libuv handle. The completion
    * callback rides through a `(LibuvPoller, cb)` closure in the handle's `data` slot so the close
    * callback can release the anchor entry before freeing the C memory.
    */
  def closeHandle(poller: LibuvPoller, handle: Ptr[Byte]): IO[Unit] =
    IO.async[Unit]: cb =>
      onOwner(poller):
        CallbackBridge.store(poller, handle, new CloseCompletion(poller, cb))
        LibUV.uv_close(handle, closeHandleCb)
        None

  /** Holder for [[closeHandle]]'s completion: carries the poller (for anchor release) and the
    * `IO.async` continuation.
    */
  final private[unsafe] class CloseCompletion(val poller: LibuvPoller, val cb: Either[Throwable, Unit] => Unit)

  /** `uv_close` callback for [[closeHandle]]: releases the anchor, frees the handle, then completes
    * the release.
    */
  private val closeHandleCb: LibUV.CloseCB = (handle: Ptr[Byte]) =>
    val completion = CallbackBridge.load[CloseCompletion](handle)
    CallbackBridge.clear(completion.poller, handle)
    stdlib.free(handle)
    completion.cb(Right(()))

end Routing

/** Storage of a callback holder in a libuv handle's or request's `data` slot, paired with a
  * GC-reachability anchor in the owning [[LibuvPoller.anchors]] map.
  * `Intrinsics.castObjectToRawPtr` is a pure reinterpretation, so without the anchor a stored
  * holder is collectible the moment its last Scala-side reference goes out of scope - cf.
  * cats-effect `EpollSystem`'s `handles` map. [[clear]] / [[releaseReq]] remove the anchor when the
  * slot is released; the trampoline-side [[load]] / [[loadReq]] recover the holder via the raw
  * pointer.
  */
private[emile] object CallbackBridge:

  /** Store `holder` in `handle`'s `uv_handle->data` slot and anchor it in `poller.anchors`. */
  inline def store(poller: LibuvPoller, handle: Ptr[Byte], holder: AnyRef): Unit =
    poller.anchors.put(addrOf(handle), holder): Unit
    LibUV.uv_handle_set_data(handle, fromRawPtr[Byte](Intrinsics.castObjectToRawPtr(holder)))

  /** Recover the holder previously [[store]]d in `handle`'s `data` slot. */
  inline def load[H <: AnyRef](handle: Ptr[Byte]): H =
    // FFI recovery: the slot holds exactly the H that `store` placed there.
    Intrinsics.castRawPtrToObject(toRawPtr(LibUV.uv_handle_get_data(handle))).asInstanceOf[H] // scalafix:ok

  /** Clear `handle`'s `data` slot and release its anchor entry. */
  inline def clear(poller: LibuvPoller, handle: Ptr[Byte]): Unit =
    poller.anchors.remove(addrOf(handle)): Unit
    LibUV.uv_handle_set_data(handle, fromRawPtr[Byte](Intrinsics.castLongToRawPtr(0L)))

  /** Store `holder` in `req`'s `uv_req->data` slot and anchor it in `poller.anchors`. */
  inline def storeReq(poller: LibuvPoller, req: Ptr[Byte], holder: AnyRef): Unit =
    poller.anchors.put(addrOf(req), holder): Unit
    LibUV.uv_req_set_data(req, fromRawPtr[Byte](Intrinsics.castObjectToRawPtr(holder)))

  /** Release a request's anchor entry - paired one-to-one with [[storeReq]]; the request's `data`
    * slot is freed alongside the request itself, so no slot null-out is needed.
    */
  inline def releaseReq(poller: LibuvPoller, req: Ptr[Byte]): Unit =
    poller.anchors.remove(addrOf(req)): Unit

  /** Recover the holder previously [[storeReq]]d in `req`'s `data` slot. */
  inline def loadReq[H <: AnyRef](req: Ptr[Byte]): H =
    // FFI recovery: the slot holds exactly the H that `storeReq` placed there.
    Intrinsics.castRawPtrToObject(toRawPtr(LibUV.uv_req_get_data(req))).asInstanceOf[H] // scalafix:ok

  private inline def addrOf(ptr: Ptr[Byte]): Long =
    Intrinsics.castRawPtrToLong(toRawPtr(ptr))

end CallbackBridge
