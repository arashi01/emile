/*
 * Copyright 2025 the original author(s).
 * SPDX-License-Identifier: MIT
 */
package io.github.arashi01.emile

import _root_.io.github.arashi01.emile.unsafe.CallbackIdUtils
import _root_.io.github.arashi01.emile.unsafe.CallbackRegistry
import _root_.io.github.arashi01.emile.unsafe.LibUV
import _root_.io.github.arashi01.emile.unsafe.SignalBridge as Bridge

import scala.scalanative.posix.errno as posixErrno
import scala.scalanative.unsafe.*

/**
 * Signal watcher using the async-signal-safe bridge.
 *
 * Unlike the libuv-native `Signal` handle, this implementation uses a custom
 * C bridge that installs a POSIX signal handler which calls `uv_async_send`.
 * This provides a cleaner integration for repeated signal handling with
 * callback-based APIs.
 *
 * == Why This Exists ==
 *
 * The libuv Signal handle works well for simple cases, but when integrating
 * with cats-effect's streaming APIs (like Queue.unsafeOffer), the paradigm
 * mismatch between POSIX signals and the Scala runtime can cause issues.
 *
 * This bridge uses `uv_async_send` which is explicitly documented as
 * async-signal-safe in libuv, providing a safe notification mechanism that
 * runs callbacks during normal event loop iteration.
 *
 * == Thread Safety ==
 *
 * The signal handler runs in signal context but only calls `uv_async_send`,
 * which is async-signal-safe. The actual Scala callback runs during normal
 * loop iteration on the loop thread.
 *
 * == Lifecycle ==
 *
 * Unlike handle-based APIs, this uses a registration model:
 * - `watch` registers a signal and returns the async handle
 * - `unwatch` stops watching and cleans up
 *
 * Only one watcher can be registered per signal number at a time.
 */
object SignalWatcher:

  /**
   * Start watching for a signal.
   *
   * Installs a signal handler that uses `uv_async_send` to notify the
   * event loop when the signal arrives. The callback is invoked during
   * normal loop iteration.
   *
   * @param loop The event loop
   * @param signum The signal number to watch
   * @param callback The callback to invoke when the signal arrives
   * @return Either an error or the async handle (for closing)
   */
  def watch(loop: Loop)(signum: Int)(callback: () => Unit): Either[EmileError, Ptr[Byte]] =
    Zone:
      val outAsync: Ptr[Ptr[Byte]] = stackalloc[Ptr[Byte]]()

      // Register callback to be invoked when signal arrives
      val callbackId = CallbackRegistry.registerLoop(loop.ptrUnsafe, callback)

      val result = Bridge.watch(loop.ptrUnsafe, signum, watcherCallback, outAsync)
      if result < 0 then
        val _ = CallbackRegistry.unregister(loop.ptrUnsafe, callbackId)
        if result == -posixErrno.EBUSY then Left(EmileError.SignalAlreadyWatched(signum))
        else if result == -posixErrno.EINVAL then Left(EmileError.InvalidSignal(signum))
        else if result == -posixErrno.ENOMEM then Left(EmileError.OutOfMemory)
        else Left(EmileError.fromErrorCode(ErrorCode(result)))
      else
        val asyncHandle = !outAsync
        // Store callback ID in the async handle's data pointer
        CallbackIdUtils.setCallbackId(asyncHandle, callbackId)
        Right(asyncHandle)

  /**
   * Stop watching a signal.
   *
   * Restores the previous signal handler and cleans up resources.
   *
   * @param loop The event loop (for callback unregistration)
   * @param signum The signal number to stop watching
   * @return Either an error or success
   */
  def unwatch(loop: Loop)(signum: Int): Either[EmileError, Unit] =
    // Get the async handle to unregister the callback
    val asyncHandle = Bridge.getAsync(signum)
    if asyncHandle != null then // scalafix:ok; FFI null check
      val callbackId = CallbackIdUtils.getCallbackId(asyncHandle)
      if callbackId != 0L then
        val _ = CallbackRegistry.unregister(loop.ptrUnsafe, callbackId)

    val result = Bridge.unwatch(signum)
    if result < 0 then
      if result == -posixErrno.ENOENT then Left(EmileError.SignalNotWatched(signum))
      else if result == -posixErrno.EINVAL then Left(EmileError.InvalidSignal(signum))
      else Left(EmileError.fromErrorCode(ErrorCode(result)))
    else Right(())

  /**
   * Check if a signal is currently being watched.
   *
   * @param signum The signal number to check
   * @return true if the signal is being watched
   */
  def isWatched(signum: Int): Boolean =
    Bridge.isWatched(signum) != 0

  /** The async callback that invokes the registered Scala callback. */
  private val watcherCallback: Bridge.AsyncCallback = (handle: Ptr[Byte]) =>
    val loopPtr = LibUV.uv_handle_get_loop(handle)
    val callbackId = CallbackIdUtils.getCallbackId(handle)
    if callbackId != 0L then
      CallbackRegistry.findAs[() => Unit](loopPtr, callbackId).foreach { callback =>
        callback()
      }

end SignalWatcher
