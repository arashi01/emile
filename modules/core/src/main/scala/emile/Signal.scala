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

import scala.scalanative.libc.signal as clib
import scala.scalanative.posix.signal as posix

/**
 * POSIX signal constants for use with [[SignalWatcher]].
 *
 * These constants delegate to Scala Native's platform-abstracted signal
 * bindings, ensuring correct values across Linux, macOS, and BSD variants.
 *
 * == Common Signals ==
 *
 * {{{
 * SIGINT  - Interrupt from keyboard (Ctrl+C)
 * SIGTERM - Termination signal
 * SIGHUP  - Hangup signal
 * SIGUSR1 - User-defined signal 1
 * SIGUSR2 - User-defined signal 2
 * }}}
 *
 * == Example ==
 * {{{
 * // Using SignalStream in cats-effect
 * SignalStream.watch(Signal.SIGTERM).use { case (queue, ready) =>
 *   ready >> queue.take >> IO.println("Received SIGTERM")
 * }
 * }}}
 *
 * @note Signal numbers are platform-specific. These accessors return the
 *       correct values for the target platform at link time.
 */
object Signal:

  // =========================================================================
  // C Standard Signals (from libc.signal)
  // =========================================================================

  /** Abort signal - called from abort(). */
  inline def SIGABRT: Int = clib.SIGABRT

  /** Floating point exception. */
  inline def SIGFPE: Int = clib.SIGFPE

  /** Illegal instruction signal. */
  inline def SIGILL: Int = clib.SIGILL

  /** Interrupt signal - Ctrl+C pressed. */
  inline def SIGINT: Int = clib.SIGINT

  /** Segmentation fault. */
  inline def SIGSEGV: Int = clib.SIGSEGV

  /** Termination signal - graceful shutdown request. */
  inline def SIGTERM: Int = clib.SIGTERM

  // =========================================================================
  // POSIX Signals (from posix.signal)
  // =========================================================================

  /** Alarm signal from alarm(). */
  inline def SIGALRM: Int = posix.SIGALRM

  /** Bus error. */
  inline def SIGBUS: Int = posix.SIGBUS

  /** Child process terminated. */
  inline def SIGCHLD: Int = posix.SIGCHLD

  /** Continue if stopped. */
  inline def SIGCONT: Int = posix.SIGCONT

  /** Hangup signal - terminal closed or controlling process ended. */
  inline def SIGHUP: Int = posix.SIGHUP

  /** Kill signal - cannot be caught or ignored. */
  inline def SIGKILL: Int = posix.SIGKILL

  /** Broken pipe - write to pipe with no readers. */
  inline def SIGPIPE: Int = posix.SIGPIPE

  /** Quit signal - Ctrl+\ pressed. */
  inline def SIGQUIT: Int = posix.SIGQUIT

  /** Stop process - cannot be caught or ignored. */
  inline def SIGSTOP: Int = posix.SIGSTOP

  /** Terminal stop signal - Ctrl+Z pressed. */
  inline def SIGTSTP: Int = posix.SIGTSTP

  /** User-defined signal 1. */
  inline def SIGUSR1: Int = posix.SIGUSR1

  /** User-defined signal 2. */
  inline def SIGUSR2: Int = posix.SIGUSR2

  // TODO: SIGWINCH (window resize) is a BSD/System V extension not yet in
  // Scala Native's bindings. Add when upstream support is available or
  // implement via custom C helper if needed for terminal applications.

end Signal
