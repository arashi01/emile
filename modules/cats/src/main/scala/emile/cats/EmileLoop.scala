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
package emile.cats

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*

import boilerplate.effect.*
import boilerplate.effect.Eff

import emile.EmileError
import emile.Loop
import emile.LoopConfig
import emile.RunMode
import emile.cats.LibuvPollingSystem.LoopAccess

/**
 * cats-effect Resource integration for libuv event loop.
 *
 * Provides managed resource acquisition for libuv loops in a cats-effect context.
 * Requires `LibuvPollingSystem` as the runtime's polling backend.
 *
 * == Usage ==
 *
 * {{{
 * // In an EmileIOApp or runtime with LibuvPollingSystem:
 * EmileLoop.integrated.use { loop =>
 *   // Create handles, perform I/O, etc.
 *   IO.println(s"Loop alive: \${loop.isAlive}")
 * }
 * }}}
 *
 * == Thread Model ==
 *
 * Each cats-effect worker thread has its own libuv loop. `integrated` provides
 * access to the current thread's loop. Handles created on a loop belong to that
 * loop and must be used from the same thread.
 */
object EmileLoop:

  /**
   * Get the current thread's libuv loop from the runtime.
   *
   * This is the primary way to access a loop in emile-cats. The loop is
   * owned by the runtime and is already being polled - no additional
   * setup is needed.
   *
   * The loop is NOT closed when the resource is released (it belongs
   * to the runtime).
   *
   * @return Resource providing access to the current thread's loop with typed error channel
   */
  val integrated: Resource[Eff.Of[IO, EmileError], Loop] =
    Resource.eval(LoopAccess.get).flatMap(_.loop)



  /**
   * Create a standalone loop for manual management.
   *
   * Use this when you need a loop separate from the runtime's loops,
   * for example for dedicated I/O threads or testing. You are responsible
   * for running the loop (via `runOnce`, `runNoWait`, etc.).
   *
   * The loop is properly drained and closed when the resource is released.
   */
  inline def create: Resource[Eff.Of[IO, EmileError], Loop] =
    create(LoopConfig.empty)

  /**
   * Create a standalone loop with configuration.
   *
   * @param config Loop configuration options
   * @return Resource that manages loop lifecycle
   */
  def create(config: LoopConfig): Resource[Eff.Of[IO, EmileError], Loop] =
    Resource.make(
      acquire = Loop.create(config).eff[IO]
    )(
      release = loop => Eff.liftF(drainAndClose(loop))
    )

  /**
   * Drain a loop (close all handles and process callbacks) then close it.
   */
  private def drainAndClose(loop: Loop): IO[Unit] =
    for
      _ <- IO(loop.walkAndClose())
      _ <- pollUntilDrained(loop)
      _ <- IO(loop.close).void
    yield ()

  /**
   * Poll until the loop has no more active handles.
   */
  private def pollUntilDrained(loop: Loop): IO[Unit] =
    IO {
      if loop.isAlive then
        val _ = loop.run(RunMode.NoWait)
        true
      else
        false
    }.flatMap { alive =>
      if alive then IO.cede *> pollUntilDrained(loop)
      else IO.unit
    }

  // =========================================================================
  // Loop extensions for Eff-based operations
  // =========================================================================

  extension (loop: Loop)
    /**
     * Run loop once, blocking until I/O is available.
     *
     * @return true if loop still has active handles
     */
    inline def runOnce: Eff[IO, EmileError, Boolean] =
      loop.run(RunMode.Once).eff[IO]

    /**
     * Run loop until all handles are closed.
     *
     * Blocks the fiber until no active handles remain.
     */
    def runUntilComplete: Eff[IO, EmileError, Unit] =
      def drain: Eff[IO, EmileError, Unit] =
        loop.runOnce.flatMap { alive =>
          if alive then Eff.liftF(IO.cede) *> drain else Eff.succeed[IO, EmileError, Unit](())
        }
      drain

    /**
     * Run loop in non-blocking mode.
     *
     * Processes pending callbacks without waiting for I/O.
     *
     * @return true if loop still has active handles
     */
    inline def runNoWait: Eff[IO, EmileError, Boolean] =
      loop.run(RunMode.NoWait).eff[IO]

end EmileLoop
