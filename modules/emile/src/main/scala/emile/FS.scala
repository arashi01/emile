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
package emile

import scala.scalanative.libc.stdlib
import scala.scalanative.unsafe.CInt
import scala.scalanative.unsafe.CString
import scala.scalanative.unsafe.Ptr
import scala.scalanative.unsafe.Zone
import scala.scalanative.unsafe.fromCString
import scala.scalanative.unsafe.toCString
import scala.scalanative.unsigned.*

import boilerplate.effect.EffIO
import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.unsafe.UnboundedQueue
import fs2.Stream

import emile.unsafe.CallbackBridge
import emile.unsafe.LibUV
import emile.unsafe.LibUVPoller
import emile.unsafe.Routing

/** The kind of change observed on a watched path. */
enum FSChange derives CanEqual:
  case Renamed, Changed

/** A filesystem change: the kinds of change in one notification, and the affected entry's name
  * relative to the watched path when the platform supplies one (absent when it does not, e.g. for a
  * watched file reported without a name).
  */
final case class FSEvent(changes: Set[FSChange], filename: Option[String]) derives CanEqual

final private class FSState(
  val handle: Ptr[Byte],
  val poller: LibUVPoller,
  val queue: UnboundedQueue[IO, Either[EmileError.IO, FSEvent]]
)

/** A filesystem-change watcher, backed by a libuv `uv_fs_event_t` (inotify on Linux). Acquired
  * through [[FS$ FS]].
  */
opaque type FS = FSState

/** Watch construction, the change stream, and equality for [[FS]]. */
object FS:

  /** Watches `path` for changes for the resource's lifetime. The watcher is created on - and closed
    * back on - the loop of the worker the resource is acquired on. Prefer watching a directory over
    * a single file: a rename that replaces a watched file detaches the underlying inotify watch,
    * after which it stops reporting, whereas a directory watch keeps reporting its entries'
    * changes.
    */
  def watch(path: java.nio.file.Path): EmResource[EmileError.IO, FS] =
    Resource.make[EffIO.Of[EmileError.IO], FS](acquire(path))(release)

  given CanEqual[FS, FS] = CanEqual.derived

  extension (fs: FS)
    /** The changes observed on the watched path, in arrival order, until the resource releases. A
      * libuv watch error ends the stream on the [[EmileError.IO]] channel. The platform coalesces
      * rapid changes and may report a change with no entry name, so debouncing is the consumer's
      * concern.
      */
    def events: EmStream[EmileError.IO, FSEvent] =
      Stream.repeatEval[EmIO.Of[EmileError.IO], FSEvent](EffIO.lift(fs.queue.take))

  private def acquire(path: java.nio.file.Path): EmIO[EmileError.IO, FS] =
    EffIO.lift:
      for
        poller <- LibUVPollingSystem.currentPoller
        queue <- UnboundedQueue[IO, Either[EmileError.IO, FSEvent]]
        handle <- IO(allocHandle())
        started <- Routing.onOwner(poller)(install(poller, handle, path, queue))
        result <- started match
                    case Right(_) => IO.pure(started)
                    // The handle is initialised but the watch failed to start; uv_close frees it.
                    case Left(_) => Routing.closeHandle(poller, handle).as(started)
      yield result

  private def release(fs: FS): EmIO[EmileError.IO, Unit] =
    EffIO.liftF(Routing.closeHandle(fs.poller, fs.handle))

  // flags are 0: recursive watching is a no-op on Linux inotify and the other two libuv flags are
  // unimplemented on every backend, so recursion belongs to the cross-platform layer.
  private def install(
    poller: LibUVPoller,
    handle: Ptr[Byte],
    path: java.nio.file.Path,
    queue: UnboundedQueue[IO, Either[EmileError.IO, FSEvent]]
  ): Either[EmileError.IO, FS] =
    val initRc = LibUV.uv_fs_event_init(poller.loop, handle)
    if initRc != 0 then
      stdlib.free(handle)
      Left(IOMapping.fromCode(initRc))
    else
      val state = new FSState(handle, poller, queue)
      CallbackBridge.store(poller, handle, state)
      val startRc = Zone(LibUV.uv_fs_event_start(handle, fsEventCb, toCString(path.toString), 0.toUInt))
      if startRc != 0 then Left(IOMapping.fromCode(startRc))
      else Right(state)
  end install

  // uv_fs_event_t allocation: a null calloc result is OOM, surfaced by a throw.
  // scalafix:off DisableSyntax
  private def allocHandle(): Ptr[Byte] =
    val handle = stdlib.calloc(1.toCSize, LibUV.uv_handle_size(LibUV.UV_FS_EVENT))
    if handle == null then throw new OutOfMemoryError("emile: uv_fs_event_t allocation failed")
    else handle
  // scalafix:on DisableSyntax

  private def changeBit(change: FSChange): Int = change match
    case FSChange.Renamed => LibUV.UV_RENAME
    case FSChange.Changed => LibUV.UV_CHANGE

  private def decodeChanges(events: Int): Set[FSChange] =
    FSChange.values.iterator.filter(change => (events & changeBit(change)) != 0).toSet

  // libuv passes a NULL filename when the backend supplies no entry name (e.g. a watched file itself).
  // scalafix:off DisableSyntax
  private def decodeFilename(filename: CString): Option[String] =
    if filename == null then None else Some(fromCString(filename))
  // scalafix:on DisableSyntax

  // uv_fs_event_cb: build the change event and offer it to the subscriber queue; runs on the loop thread.
  private val fsEventCb: LibUV.FSEventCB = (handle: Ptr[Byte], filename: CString, events: CInt, status: CInt) =>
    val state = CallbackBridge.load[FSState](handle)
    if status < 0 then state.queue.unsafeOffer(Left(IOMapping.fromCode(status)))
    else state.queue.unsafeOffer(Right(FSEvent(decodeChanges(events), decodeFilename(filename))))

end FS
