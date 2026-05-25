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

import emile.unsafe.SignalSupervisor

/** Subscriptions to POSIX process signals. A process-singleton supervisor installs one
  * `uv_signal_t` per signal number the first time it is watched and broadcasts every delivery to
  * every subscriber, so concurrent watches of the same signal never race.
  */
@scala.annotation.internal.sharable
object Signal:

  /** A stream emitting `Unit` on each delivery of `signum` to the process. */
  def watch(signum: SignalNumber): EmStream[Nothing, Unit] =
    SignalSupervisor.subscribe(signum).translate(EffIO.liftK)

  /** A stream emitting on each `SIGINT` or `SIGTERM` - the conventional shutdown signals. */
  val termination: EmStream[Nothing, Unit] =
    watch(SignalNumber.SIGINT).merge(watch(SignalNumber.SIGTERM))
