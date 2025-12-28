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
package emile.unsafe

import scala.scalanative.unsafe.*

/**
 * FFI bindings for the Émile signal bridge.
 *
 * This provides a clean integration between POSIX signals and libuv using
 * uv_async, which is async-signal-safe.
 */
@link("uv")
@extern
object SignalBridge:

  /**
   * Register to watch a signal using the async-signal-safe uv_async mechanism.
   *
   * @param loop The libuv event loop
   * @param signum The signal number to watch
   * @param asyncCb The callback to invoke when signal arrives
   * @param outAsync Output: pointer to the created async handle
   * @return 0 on success, negative error code on failure
   */
  @name("emile_signal_watch")
  def watch(
      loop: Ptr[Byte],
      signum: CInt,
      asyncCb: AsyncCallback,
      outAsync: Ptr[Ptr[Byte]]
  ): CInt = extern

  /**
   * Stop watching a signal and clean up.
   *
   * @param signum The signal number to stop watching
   * @return 0 on success, negative error code on failure
   */
  @name("emile_signal_unwatch")
  def unwatch(signum: CInt): CInt = extern

  /**
   * Get the async handle for a watched signal.
   *
   * @param signum The signal number
   * @return The async handle pointer, or null if not watching
   */
  @name("emile_signal_get_async")
  def getAsync(signum: CInt): Ptr[Byte] = extern

  /**
   * Check if a signal is being watched.
   *
   * @param signum The signal number
   * @return 1 if watching, 0 if not
   */
  @name("emile_signal_is_watched")
  def isWatched(signum: CInt): CInt = extern

  /**
   * Callback type for async handles.
   * Matches libuv's uv_async_cb signature.
   */
  type AsyncCallback = CFuncPtr1[Ptr[Byte], Unit]

end SignalBridge
