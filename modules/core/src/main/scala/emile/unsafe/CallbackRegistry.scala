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

// scalafix:off DisableSyntax.null, DisableSyntax.var, DisableSyntax.asInstanceOf; unsafe FFI callback registry

import scala.annotation.internal.sharable
import scala.collection.mutable.LongMap
import scala.collection.mutable.Set as MutableSet
import scala.scalanative.libc.stdlib
import scala.scalanative.runtime.Intrinsics.castObjectToRawPtr
import scala.scalanative.runtime.Intrinsics.castRawPtrToObject
import scala.scalanative.runtime.fromRawPtr
import scala.scalanative.runtime.toRawPtr
import scala.scalanative.unsafe.*

/**
 * Registry for mapping stable Long IDs to Scala callback objects.
 *
 * This registry pattern provides GC-safe callback management for libuv handles.
 * Instead of storing raw object pointers in libuv's `data` field (which could become
 * invalid if the GC moves objects), we store a stable Long ID that maps to the
 * actual callback object in this registry.
 *
 * == Design Rationale ==
 *
 * - Scala Native's current Immix/Commix GC is mark-sweep (non-moving), but future
 *   GC implementations may use moving collectors
 * - Storing raw pointers to GC-managed objects in C structures is inherently unsafe
 * - The registry pattern uses stable Long IDs that remain valid regardless of GC behaviour
 * - The registry keeps callbacks rooted (preventing premature collection) while registered
 * - ID 0 is reserved as a sentinel value meaning "no callback registered"
 *
 * == Thread Safety Warning ==
 *
 * '''This implementation is NOT thread-safe.'''
 *
 * While libuv event loops are single-threaded, libuv does support running multiple
 * independent event loops on different threads simultaneously. This global registry
 * would cause data races if used with multiple loops across threads.
 *
 * '''Current limitation:''' Only use one event loop at a time, or ensure all loop
 * operations (including handle creation/destruction) happen on the same thread.
 *
 * '''Future work:''' To support multi-loop scenarios, consider:
 * - Scope a registry per loop via `uv_loop_set_data`/`uv_loop_get_data`
 * - Add synchronization (e.g., `scala.scalanative.runtime.Monitors`)
 * - Use thread-local storage for per-thread registries
 *
 * @see [[https://docs.libuv.org/en/stable/guide/basics.html#event-loops libuv Event Loops]]
 * @see [[https://scala-native.org/en/stable/user/interop.html Scala Native Interop Guide]]
 *
 * @note The `@sharable` annotation tells `-Ycheck-reentrant` that this global mutable
 *       state is intentionally shared. This suppresses the compiler warning but does
 *       NOT provide thread safety.
 */
@sharable
private[emile] object CallbackRegistry:
  private inline def uv = _root_.emile.unsafe.LibUV

  /** Native context stored on requests: (loopId, callbackId). */
  private type RequestContext = CStruct2[Ptr[Byte], CLongLong]

  /** Per-loop registry state. */
  private final class LoopRegistry(val loopPtr: Ptr[Byte], val salt: Long, var nextId: Long, val callbacks: LongMap[Any])

  /** Roots to keep registries GC-visible while loop is alive. */
  private val registryRoots: MutableSet[LoopRegistry] = MutableSet.empty

  /** Native pointers of active registries (fast membership check). */
  private val registryPtrs: MutableSet[Ptr[Byte]] = MutableSet.empty

  /** Monotonic salt to namespace ids across loops. */
  private var nextSalt: Long = 1L

  private inline def registryPtr(registry: LoopRegistry): Ptr[Byte] =
    fromRawPtr[Byte](castObjectToRawPtr(registry))

  private inline def fromRegistryPtr(ptr: Ptr[Byte]): Option[LoopRegistry] =
    if ptr == null then None else Some(castRawPtrToObject(toRawPtr(ptr)).asInstanceOf[LoopRegistry])

  /** Obtain or create the registry for a loop pointer. */
  private def registryFor(loopPtr: Ptr[Byte]): LoopRegistry =
    registryRoots.synchronized {
      val dataPtr = uv.uv_loop_get_data(loopPtr)
      if dataPtr != null && registryPtrs.contains(dataPtr) then
        fromRegistryPtr(dataPtr).get
      else
        val salt = nextSalt
        nextSalt = if nextSalt == Long.MaxValue then 1L else nextSalt + 1L
        val registry = LoopRegistry(loopPtr, salt, 1L, LongMap.empty[Any])
        val ptr = registryPtr(registry)
        registryRoots += registry
        registryPtrs += ptr
        uv.uv_loop_set_data(loopPtr, ptr)
        registry
    }

  /** Remove registry for loop and clear loop data to sentinel. */
  def clear(loopPtr: Ptr[Byte]): Unit =
    registryRoots.synchronized {
      val dataPtr = uv.uv_loop_get_data(loopPtr)
      if dataPtr != null && registryPtrs.contains(dataPtr) then
        fromRegistryPtr(dataPtr).foreach { registry =>
          val _ = registryRoots.remove(registry)
          val _ = registryPtrs.remove(dataPtr)
        }
      uv.uv_loop_set_data(loopPtr, null.asInstanceOf[Ptr[Byte]])
    }

  /** Clear all registries (testing only). */
  def clearAll(): Unit =
    registryRoots.synchronized {
      registryRoots.foreach { registry =>
        uv.uv_loop_set_data(registry.loopPtr, null.asInstanceOf[Ptr[Byte]])
      }
      registryRoots.clear()
      registryPtrs.clear()
      nextSalt = 1L
    }

  /** Register a callback for the loop owning the handle. */
  def register(handle: Ptr[Byte], callback: Any): Long =
    registerLoop(uv.uv_handle_get_loop(handle), callback)

  /** Register a callback for a loop pointer. */
  def registerLoop(loopPtr: Ptr[Byte], callback: Any): Long =
    val registry = registryFor(loopPtr)
    registry.synchronized {
      val localId = registry.nextId
      registry.nextId = localId + 1
      val id = (registry.salt << 32) | (localId & 0xFFFFFFFFL)
      val _ = registry.callbacks.put(id, callback)
      id
    }

  /** Find callback for loop/id. */
  def find(loopPtr: Ptr[Byte], id: Long): Option[Any] =
    if id == 0L then None
    else
      val registry = registryFor(loopPtr)
      registry.callbacks.synchronized(registry.callbacks.get(id))

  def findAs[A](loopPtr: Ptr[Byte], id: Long): Option[A] =
    find(loopPtr, id).map(_.asInstanceOf[A])

  /** Unregister callback for loop/id. */
  def unregister(loopPtr: Ptr[Byte], id: Long): Boolean =
    if id == 0L then false
    else
      val registry = registryFor(loopPtr)
      registry.callbacks.synchronized(registry.callbacks.remove(id).isDefined)

  /** Total callbacks in a loop. */
  def size(loopPtr: Ptr[Byte]): Int =
    val registry = registryFor(loopPtr)
    registry.callbacks.synchronized(registry.callbacks.size)

  /** Aggregate size across loops (diagnostics/testing). */
  def totalSize: Int =
    registryRoots.synchronized(registryRoots.toSeq.map(_.callbacks.size).sum)

  /** Encode loop pointer + callback id for request data. */
  def encodeRequest(loopPtr: Ptr[Byte], callbackId: Long): Ptr[Byte] =
    val ctx = stdlib.malloc(sizeof[RequestContext]).asInstanceOf[Ptr[RequestContext]]
    if ctx != null then
      ctx._1 = loopPtr
      ctx._2 = callbackId
    ctx.asInstanceOf[Ptr[Byte]]

  def requestLoopPtr(ctx: Ptr[Byte]): Ptr[Byte] =
    if ctx == null then null.asInstanceOf[Ptr[Byte]]
    else ctx.asInstanceOf[Ptr[RequestContext]]._1

  def requestCallbackId(ctx: Ptr[Byte]): Long =
    if ctx == null then 0L else ctx.asInstanceOf[Ptr[RequestContext]]._2.toLong

  def freeRequest(ctx: Ptr[Byte]): Unit =
    if ctx != null then stdlib.free(ctx)

end CallbackRegistry

/**
 * Utilities for storing callback IDs in libuv handle data pointers.
 *
 * These utilities convert between Long callback IDs and the raw pointers
 * that libuv expects in its `data` fields.
 *
 * We store the Long ID directly as a pointer value (reinterpreting the bits).
 * This is safe because:
 * 1. We never dereference these "pointers" - they're just opaque storage
 * 2. libuv's data field is just void* storage, not actual memory access
 * 3. On 64-bit systems, pointers and Long are both 64 bits
 */
private[emile] object CallbackIdUtils:
  private inline def uv = _root_.emile.unsafe.LibUV
  import scala.scalanative.runtime.Intrinsics.{castLongToRawPtr, castRawPtrToLong}
  import scala.scalanative.runtime.{fromRawPtr, toRawPtr}

  /**
   * Convert a Long callback ID to a Ptr[Byte] for storage in libuv data field.
   *
   * This reinterprets the Long bits as a pointer value (zero allocation).
   */
  inline def idToPtr(id: Long): Ptr[Byte] =
    fromRawPtr[Byte](castLongToRawPtr(id))

  /**
   * Convert a Ptr[Byte] from libuv data field back to a Long callback ID.
   *
   * This reinterprets the pointer bits as a Long value (zero allocation).
   */
  inline def ptrToId(ptr: Ptr[Byte]): Long =
    castRawPtrToLong(toRawPtr(ptr))

  /**
   * Set a callback ID on a libuv handle's data field.
   *
   * Uses libuv's uv_handle_set_data API for correct access.
   *
   * @param handle The handle pointer
   * @param id The callback ID to store
   */
  inline def setCallbackId(handle: Ptr[Byte], id: Long): Unit =
    uv.uv_handle_set_data(handle, idToPtr(id))

  /**
   * Get the callback ID from a libuv handle's data field.
   *
   * Uses libuv's uv_handle_get_data API for correct access.
   *
   * @param handle The handle pointer
   * @return The stored callback ID
   */
  inline def getCallbackId(handle: Ptr[Byte]): Long =
    ptrToId(uv.uv_handle_get_data(handle))

  /**
   * Clear the callback ID from a libuv handle's data field.
   *
   * @param handle The handle pointer
   */
  inline def clearCallbackId(handle: Ptr[Byte]): Unit =
    uv.uv_handle_set_data(handle, idToPtr(0L))

end CallbackIdUtils
