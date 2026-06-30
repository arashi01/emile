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
import scala.scalanative.unsafe.Ptr
import scala.scalanative.unsigned.*

import boilerplate.effect.EffIO
import cats.effect.IO
import cats.effect.Resource
import fs2.Stream

import emile.unsafe.CallbackBridge
import emile.unsafe.LibUV
import emile.unsafe.LibUVPoller
import emile.unsafe.LiveHandle
import emile.unsafe.Routing

/** A readiness condition on a polled file descriptor. */
enum FDEvent derives CanEqual:
  case Readable, Writable, Disconnect, Prioritized

final private class FDPollState(val live: LiveHandle, val eventMask: Int):
  // Single-waiter guard: a concurrent await/awaits would overwrite this one's continuation in the
  // handle slot. Owner-confined - set/cleared only inside Routing.onOwner and the poll callback,
  // both on the loop thread - so a plain var with no barrier, as the socket transfer flags are.
  var waiting: Boolean = false // scalafix:ok DisableSyntax.var

/** A file-descriptor readiness watcher, backed by a libuv `uv_poll_t`. Acquired through
  * [[FDPoll$ FDPoll]]. Readiness is a single shared condition, so a watcher serves one waiter: a
  * concurrent `await` / `awaits` on the same [[FDPoll]] fails fast with
  * [[EmileError.IO.ConflictingOperation]]. Consume from one fibre.
  */
opaque type FDPoll = FDPollState

/** Resource, the one-shot and persistent readiness operations, and equality for [[FDPoll]]. */
object FDPoll:

  /** A scoped `uv_poll_t` watching `fd` for `events`. The handle is created on - and closed back on -
    * the loop of the worker the resource is acquired on. The descriptor must stay open for the
    * resource's lifetime.
    */
  def resource(fd: Int, events: Set[FDEvent]): EmResource[EmileError.IO, FDPoll] =
    Resource.make[EffIO.Of[EmileError.IO], FDPoll](acquire(fd, events))(release)

  given CanEqual[FDPoll, FDPoll] = CanEqual.derived

  extension (poll: FDPoll)
    /** Completes with the readiness conditions for which the descriptor next becomes ready.
      * One-shot: polling is armed for this call and stopped when the events arrive or the effect is
      * cancelled. Using the watcher after its resource has released is a typed
      * [[EmileError.IO.AlreadyClosed]].
      */
    def await: EmIO[EmileError.IO, Set[FDEvent]] =
      EffIO.attempt(startPoll(poll), EmileError.IO.Unexpected(_))

    /** A readiness stream holding the one handle across deliveries, rather than re-acquiring per
      * readiness - for an external descriptor watched repeatedly. The watch is disarmed between
      * elements and re-armed on the next pull, so a slow consumer cannot busy-loop the
      * level-triggered poll, while re-arming still catches a descriptor that is already ready. A
      * poll error ends the stream on the [[EmileError.IO]] channel.
      */
    def awaits: EmStream[EmileError.IO, Set[FDEvent]] =
      Stream.repeatEval[EmIO.Of[EmileError.IO], Set[FDEvent]](poll.await)
  end extension

  private def poller(poll: FDPoll): LibUVPoller = LiveHandle.poller(poll.live)

  private def acquire(fd: Int, events: Set[FDEvent]): EmIO[EmileError.IO, FDPoll] =
    EffIO.lift:
      for
        poller <- LibUVPollingSystem.currentPoller
        handle <- IO(allocHandle())
        result <- Routing.onOwner(poller)(install(poller, handle, fd, events))
      yield result

  private def release(poll: FDPoll): EmIO[EmileError.IO, Unit] =
    EffIO.liftF(LiveHandle.closeOnOwner(poll.live))

  private def install(
    poller: LibUVPoller,
    handle: Ptr[Byte],
    fd: Int,
    events: Set[FDEvent]
  ): Either[EmileError.IO, FDPoll] =
    val rc = LibUV.uv_poll_init(poller.loop, handle, fd)
    if rc != 0 then
      stdlib.free(handle)
      Left(IOMapping.fromCode(rc))
    else Right(new FDPollState(LiveHandle(poller, handle), eventMask(events)))

  private def startPoll(poll: FDPoll): IO[Set[FDEvent]] =
    IO.async[Set[FDEvent]]: cb =>
      Routing.onOwner(poller(poll)):
        LiveHandle.tryUse(poll.live, closedAsync(cb)): handle =>
          if poll.waiting then
            // A wait is already in flight on this watcher; a second would strand the first's callback.
            cb(Left(EmileError.IO.ConflictingOperation))
            None
          else
            poll.waiting = true
            CallbackBridge.store(poller(poll), handle, new PollHolder(poll, cb))
            val rc = LibUV.uv_poll_start(handle, poll.eventMask, pollCb)
            if rc < 0 then
              stopPoll(poll, handle)
              cb(Left(IOMapping.fromCode(rc)))
              None
            else Some(Routing.onOwner(poller(poll))(LiveHandle.tryUse(poll.live, ())(handle => stopPoll(poll, handle))))

  // tryUse closed branch for the IO.async: fail the callback and register no finaliser. By-name in
  // tryUse, so it fires only when the handle is already closed.
  private def closedAsync(cb: Either[Throwable, Set[FDEvent]] => Unit): Option[IO[Unit]] =
    cb(Left(EmileError.IO.AlreadyClosed))
    None

  // Both the one-shot trampoline and the cancellation finaliser run this; uv_poll_stop is idempotent.
  // Clears the single-waiter flag so the next await may arm, and releases the handle's anchor.
  private def stopPoll(state: FDPollState, handle: Ptr[Byte]): Unit =
    LibUV.uv_poll_stop(handle): Unit
    state.waiting = false
    CallbackBridge.clear(LiveHandle.poller(state.live), handle)

  // Carries the state so the trampoline's stopPoll can clear the anchor and the single-waiter flag.
  final private class PollHolder(val state: FDPollState, val cb: Either[Throwable, Set[FDEvent]] => Unit)

  private def eventBit(event: FDEvent): Int = event match
    case FDEvent.Readable => LibUV.UV_READABLE
    case FDEvent.Writable => LibUV.UV_WRITABLE
    case FDEvent.Disconnect => LibUV.UV_DISCONNECT
    case FDEvent.Prioritized => LibUV.UV_PRIORITIZED

  private def eventMask(events: Set[FDEvent]): Int =
    events.foldLeft(0)((mask, event) => mask | eventBit(event))

  private def decodeEvents(mask: Int): Set[FDEvent] =
    FDEvent.values.iterator.filter(event => (mask & eventBit(event)) != 0).toSet

  // uv_poll_t allocation: a null calloc result is OOM, surfaced by a throw.
  // scalafix:off DisableSyntax
  private def allocHandle(): Ptr[Byte] =
    val handle = stdlib.calloc(1.toCSize, LibUV.uv_handle_size(LibUV.UV_POLL))
    if handle == null then throw new OutOfMemoryError("emile: uv_poll_t allocation failed")
    else handle
  // scalafix:on DisableSyntax

  // uv_poll_cb: deliver the readiness event off the live handle libuv passed, then stop the one-shot
  // poll. Stopping before completing the callback leaves the watch disarmed between deliveries.
  private val pollCb: LibUV.PollCB = (handle: Ptr[Byte], status: CInt, events: CInt) =>
    val holder = CallbackBridge.load[PollHolder](handle)
    stopPoll(holder.state, handle)
    if status < 0 then holder.cb(Left(IOMapping.fromCode(status)))
    else holder.cb(Right(decodeEvents(events)))

end FDPoll
