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

import boilerplate.effect.EffIO
import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.effect.std.Queue
import fs2.Stream

final private class AsyncSignalState(val wakeups: Queue[IO, Unit], val consuming: Ref[IO, Boolean])

/** A cross-fibre / cross-thread wake-up: a [[AsyncSignal$ AsyncSignal]].fire from anywhere surfaces
  * on the [[AsyncSignal$ AsyncSignal]].fires stream. A cats-effect-backed convenience - typed
  * errors, a `Resource` lifecycle, and coalescing - not a libuv handle. It coalesces like an
  * edge-triggered signal: a capacity-one circular buffer keeps only the latest pending wake-up, so
  * N rapid fires surface as M (`<=` N) elements. The stream is drained by a single subscriber.
  */
opaque type AsyncSignal = AsyncSignalState

/** Resource, the fire / fires operations, and equality for [[AsyncSignal]]. */
object AsyncSignal:

  /** A scoped wake-up. It holds nothing native, so the resource only scopes the signal's lifetime. */
  def resource: EmResource[EmileError.IO, AsyncSignal] =
    Resource.eval(acquire)

  given CanEqual[AsyncSignal, AsyncSignal] = CanEqual.derived

  extension (signal: AsyncSignal)
    /** Wakes a pending [[fires]] taker, from any fibre or thread. Coalescing: an offer to the
      * capacity-one buffer replaces an unconsumed wake-up rather than queueing, so rapid fires
      * collapse - an edge-triggered signal, not a counter.
      */
    def fire: EmIO[EmileError.IO, Unit] =
      EffIO.liftF(signal.wakeups.offer(()))

    /** The wake-up stream, until the resource releases. Drained by one subscriber: a second
      * concurrent `fires` fails fast with [[EmileError.IO.ConflictingOperation]].
      */
    def fires: EmStream[EmileError.IO, Unit] =
      Stream
        .resource(consumer(signal))
        .flatMap(_ => Stream.repeatEval[EmIO.Of[EmileError.IO], Unit](EffIO.liftF(signal.wakeups.take)))
  end extension

  private def acquire: EmIO[EmileError.IO, AsyncSignal] =
    EffIO.liftF(
      for
        wakeups <- Queue.circularBuffer[IO, Unit](1)
        consuming <- IO.ref(false)
      yield new AsyncSignalState(wakeups, consuming)
    )

  // Claims the single-consumer slot for the fires stream's scope, failing fast if already taken.
  // Ref-based, not an owner-confined var: the rerouted signal has no loop thread to route through.
  private def consumer(signal: AsyncSignalState): EmResource[EmileError.IO, Unit] =
    Resource.make[EffIO.Of[EmileError.IO], Unit](
      EffIO.lift(signal.consuming.modify(consuming => if consuming then (true, claimed) else (true, free)))
    )(_ => EffIO.liftF(signal.consuming.set(false)))

  private val claimed: Either[EmileError.IO, Unit] = Left(EmileError.IO.ConflictingOperation)
  private val free: Either[EmileError.IO, Unit] = Right(())

end AsyncSignal
