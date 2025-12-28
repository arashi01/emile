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

import scala.scalanative.libc.stdlib.free
import scala.scalanative.unsafe.*

import emile.unsafe.CallbackIdUtils
import emile.unsafe.CallbackRegistry
import emile.unsafe.LibUV

/**
 * Type class for libuv handle operations.
 *
 * All handle types share these base operations for lifecycle management,
 * reference counting, and loop access.
 *
 * @tparam H The handle type
 */
trait Handle[H]:
  extension (h: H)
    /**
     * Close the handle asynchronously.
     *
     * This is the safe way to dispose of a handle. The callback will be
     * invoked when the close is complete.
     */
    def close(callback: Either[EmileError, Unit] => Unit): Unit

    /**
     * Close the handle without callback.
     *
     * Note: The close is still asynchronous, but no notification is provided.
     */
    def close: Either[EmileError, Unit]

    /**
     * Close the handle synchronously (no callback) with consistent semantics.
     */
    def closeSync: Either[EmileError, Unit]

    /**
     * Check if handle is active.
     *
     * What "active" means depends on the handle type:
     * - TCP: listening, connected, or reading
     * - Timer: started and not stopped
     * - Async: always active
     */
    def isActive: Boolean

    /**
     * Check if handle is closing or closed.
     *
     * Once a handle is closing, no operations should be performed on it.
     */
    def isClosing: Boolean

    /**
     * Reference the handle.
     *
     * Referenced handles keep the event loop alive. By default, handles
     * are referenced when created.
     */
    def ref: Unit

    /**
     * Unreference the handle.
     *
     * Unreferenced handles do not keep the event loop alive. Useful for
     * long-running background handles.
     */
    def unref: Unit

    /**
     * Check if handle is referenced.
     *
     * @return true if handle is referenced
     */
    def hasRef: Boolean

    /**
     * Get the owning event loop.
     *
     * @return The loop this handle belongs to
     */
    def loop: Loop

    /**
     * Get handle type.
     *
     * @return The type of this handle
     */
    def handleType: HandleType

    /**
     * Get the raw pointer for advanced usage.
     */
    def ptrUnsafe: Ptr[Byte]
end Handle

object Handle:
  /**
   * Summon Handle instance for type H.
   *
   * Uses `transparent inline` to preserve the specific instance type,
   * enabling better type inference at call sites.
   */
  transparent inline def apply[H](using h: Handle[H]): Handle[H] = h

  /**
   * Create a Handle instance from a raw pointer type.
   *
   * This provides the common implementation for all handle types.
   */
  private[emile] def fromPtr[H](toPtr: H => Ptr[Byte]): Handle[H] = new Handle[H]:
    extension (h: H)
      def close(callback: Either[EmileError, Unit] => Unit): Unit =
        val ptr = toPtr(h)
        val loopPtr = LibUV.uv_handle_get_loop(ptr)
        if LibUV.uv_is_closing(ptr) != 0 then
          callback(Left(EmileError.AlreadyClosed))
        else
          // First, unregister any existing callback (e.g., async callback)
          val existingId = CallbackIdUtils.getCallbackId(ptr)
          if existingId != 0L then
            val _ = CallbackRegistry.unregister(loopPtr, existingId)

          // Now register the close callback and store its ID
          val callbackId = CallbackRegistry.registerLoop(loopPtr, callback)
          CallbackIdUtils.setCallbackId(ptr, callbackId)
          LibUV.uv_close(ptr, closeCallback)

      def close: Either[EmileError, Unit] =
        closeSync

      def closeSync: Either[EmileError, Unit] =
        val ptr = toPtr(h)
        val loopPtr = LibUV.uv_handle_get_loop(ptr)
        if LibUV.uv_is_closing(ptr) != 0 then Left(EmileError.AlreadyClosed)
        else
          val existingId = CallbackIdUtils.getCallbackId(ptr)
          if existingId != 0L then
            val _ = CallbackRegistry.unregister(loopPtr, existingId)
            CallbackIdUtils.clearCallbackId(ptr)

          LibUV.uv_close(ptr, nullCloseCallback)
          Right(())

      def isActive: Boolean =
        LibUV.uv_is_active(toPtr(h)) != 0

      def isClosing: Boolean =
        LibUV.uv_is_closing(toPtr(h)) != 0

      def ref: Unit =
        LibUV.uv_ref(toPtr(h))

      def unref: Unit =
        LibUV.uv_unref(toPtr(h))

      def hasRef: Boolean =
        LibUV.uv_has_ref(toPtr(h)) != 0

      def loop: Loop =
        LibUV.uv_handle_get_loop(toPtr(h)).asInstanceOf[Loop] // scalafix:ok; libuv returns opaque Ptr

      def handleType: HandleType =
        HandleType.fromLibuv(LibUV.uv_handle_get_type(toPtr(h)))

      def ptrUnsafe: Ptr[Byte] = toPtr(h)

  /** Close callback that invokes the stored Scala callback via registry, then frees memory. */
  private[emile] val closeCallback: LibUV.CloseCB = (handle: Ptr[Byte]) =>
    val loopPtr = LibUV.uv_handle_get_loop(handle)
    val callbackId = CallbackIdUtils.getCallbackId(handle)
    CallbackRegistry.findAs[Either[EmileError, Unit] => Unit](loopPtr, callbackId).foreach { callback =>
      val _ = CallbackRegistry.unregister(loopPtr, callbackId)
      CallbackIdUtils.clearCallbackId(handle)
      callback(Right(()))
    }
    // Free the handle memory that was allocated with malloc
    free(handle)

  /** Close callback that frees memory (for close without user callback). */
  private[emile] val nullCloseCallback: LibUV.CloseCB = (handle: Ptr[Byte]) =>
    val loopPtr = LibUV.uv_handle_get_loop(handle)
    val existingId = CallbackIdUtils.getCallbackId(handle)
    if existingId != 0L then
      val _ = CallbackRegistry.unregister(loopPtr, existingId)
      CallbackIdUtils.clearCallbackId(handle)
    // Free the handle memory that was allocated with malloc
    free(handle)
end Handle
