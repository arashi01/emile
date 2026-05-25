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

import scala.concurrent.duration.*
import scala.scalanative.libc.signal as clib
import scala.scalanative.posix.signal as posix

import cats.effect.IO

/** Covers the signal supervisor's broadcast: a single delivery reaches every concurrent watcher of
  * the signal. `SIGURG` is used - its default disposition is to be ignored.
  */
final class SignalSupervisorSpec extends EmileSuite:

  test("a raised signal reaches every concurrent watcher") {
    val watch = Signal.watch(SignalNumber(posix.SIGURG)).head.compile.drain.absolve
    val raise = IO.sleep(250.millis) *> IO(clib.raise(posix.SIGURG): Unit)
    watch.both(watch).both(raise).timeout(5.seconds).void
  }
