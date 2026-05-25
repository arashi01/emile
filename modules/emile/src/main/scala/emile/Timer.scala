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

import scala.concurrent.duration.FiniteDuration

import boilerplate.effect.EffIO
import cats.effect.IO
import fs2.Stream

/** Cancelable delays and periodic ticks. */
object Timer:

  /** Sleeps for `delay`, then completes; cancelable. Backed by the cats-effect work-stealing pool's
    * per-worker timer heap, not a `uv_timer_t`.
    */
  def after(delay: FiniteDuration): EmIO[Nothing, Unit] =
    EffIO.liftF(IO.sleep(delay))

  /** A stream emitting `Unit` once per `period`, the first emission one `period` in. */
  def interval(period: FiniteDuration): EmStream[Nothing, Unit] =
    Stream.fixedRate[EffIO.Of[Nothing]](period)
