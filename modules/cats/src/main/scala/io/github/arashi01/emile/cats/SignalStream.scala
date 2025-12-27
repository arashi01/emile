/*
 * Copyright 2025 the original author(s).
 * SPDX-License-Identifier: MIT
 */
package io.github.arashi01.emile.cats

import boilerplate.effect.Eff
import cats.effect.Deferred
import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.Queue
import cats.effect.std.unsafe.UnboundedQueue
import io.github.arashi01.emile.EmileError
import io.github.arashi01.emile.Loop
import io.github.arashi01.emile.SignalWatcher

import scala.scalanative.unsafe.Ptr

/**
 * cats-effect integration for signal watching using the async-signal-safe bridge.
 *
 * This uses a C bridge that calls `uv_async_send` from the signal handler,
 * ensuring the Scala callback runs during normal event loop iteration rather
 * than in signal context.
 *
 * == Design Note ==
 *
 * These APIs do NOT take an implicit `Loop` parameter. Instead, they acquire
 * the loop internally at the point of use via `EmileLoop.integrated`. This is
 * essential because cats-effect's work-stealing scheduler can migrate fibers
 * between workers at any async boundary - a captured loop reference would
 * become invalid after migration.
 *
 * == Eff Pattern ==
 *
 * All public APIs return `Eff[IO, EmileError, A]` or use `Resource[Eff.Of[IO, EmileError], A]`.
 * Async callback registration uses `Eff.lift(IO.async[Either[EmileError, A]] { ... })`
 * which is the standard pattern for async operations in boilerplate-effect.
 *
 * == Thread Safety ==
 *
 * All callbacks run on the libuv event loop thread, which is the same
 * thread where cats-effect fibers execute when using LibuvPollingSystem.
 */
object SignalStream:

  /**
   * Create a resource for watching signals with a queue-based notification.
   *
   * The queue will have units offered each time the signal is received.
   * Use `queue.take` to await signals.
   *
   * The returned tuple includes an `IO[Unit]` that completes when the signal
   * handler is installed. Use this to synchronise before sending signals.
   *
   * @param signum The signal number to watch
   * @return A resource providing (queue, ready) where ready signals handler installation
   */
  def watch(signum: Int): Resource[Eff.Of[IO, EmileError], (Queue[IO, Unit], IO[Unit])] =
    EmileLoop.integrated.flatMap { loop =>
      Resource.make(
        acquire = createWatcher(loop, signum)
      )(
        release = releaseWatcher(loop, signum)
      ).map { case (_, queue, ready) => (queue, ready) }
    }

  private def createWatcher(
      loop: Loop,
      signum: Int
  ): Eff[IO, EmileError, (Ptr[Byte], UnboundedQueue[IO, Unit], IO[Unit])] =
    for
      // Create unbounded queue with unsafeOffer support
      queue <- Eff.liftF[IO, EmileError, UnboundedQueue[IO, Unit]](Queue.unsafeUnbounded[IO, Unit])
      // Create readiness gate - completes when handler is installed
      ready <- Eff.liftF[IO, EmileError, Deferred[IO, Unit]](Deferred[IO, Unit])
      // Start signal watcher using the C bridge
      // Use Eff.lift(IO.delay(...)) for synchronous Either-returning side effects
      asyncHandle <- Eff.lift[IO, EmileError, Ptr[Byte]](
        IO.delay {
          SignalWatcher.watch(loop)(signum) { () =>
            // This runs during normal loop iteration via uv_async callback
            // unsafeOffer is synchronous and thread-safe
            queue.unsafeOffer(())
          }
        }
      )
      // Signal that handler is now installed
      _ <- Eff.liftF[IO, EmileError, Boolean](ready.complete(()))
    yield (asyncHandle, queue, ready.get)

  // The triple is captured by Resource but unwatch looks up handle by signum from global slots.
  // We use @unused since the handle identity is managed by SignalWatcher internally.
  private def releaseWatcher(loop: Loop, signum: Int)(
      @scala.annotation.unused triple: (Ptr[Byte], UnboundedQueue[IO, Unit], IO[Unit])
  ): Eff[IO, EmileError, Unit] =
    Eff.lift[IO, EmileError, Unit](IO.delay(SignalWatcher.unwatch(loop)(signum)))

  /**
   * Await a single signal occurrence.
   *
   * Creates a watcher, waits for the signal to arrive, then cleans up.
   *
   * @param signum The signal number to wait for
   * @return Eff that completes when the signal is received
   */
  def awaitOnce(signum: Int): Eff[IO, EmileError, Unit] =
    // Use the watch resource with a single take - this keeps the loop resource open
    watch(signum).use { case (queue, _) => Eff.liftF(queue.take) }

end SignalStream
