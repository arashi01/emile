/*
 * Copyright 2025 the original author(s).
 * SPDX-License-Identifier: MIT
 */
package io.github.arashi01.emile.cats

import boilerplate.effect.*
import boilerplate.effect.Eff
import cats.effect.IO
import cats.effect.Resource
import cats.effect.unsafe.PollResult as CatsPollResult
import cats.effect.unsafe.PollingContext
import cats.effect.unsafe.PollingSystem
import cats.effect.unsafe.metrics.PollerMetrics
import io.github.arashi01.emile.EmileError
import io.github.arashi01.emile.Loop
import io.github.arashi01.emile.LoopConfig
import io.github.arashi01.emile.PollResult
import io.github.arashi01.emile.Poller as EmilePoller

/**
 * cats-effect PollingSystem implementation backed by libuv.
 *
 * This makes libuv THE event loop for the cats-effect runtime, eliminating
 * the paradigm mismatch between libuv's callback-based model and cats-effect's
 * polling abstraction.
 *
 * == Architecture ==
 *
 * Each cats-effect worker thread gets its own `Poller` (which owns a libuv loop).
 * The polling system:
 *
 * 1. Creates pollers on demand via `makePoller()`
 * 2. Polls for I/O via `poll()` which calls libuv's `uv_run()`
 * 3. Interrupts blocked polls via `interrupt()` using `uv_async_send()`
 * 4. Cleans up via `closePoller()` and `close()`
 *
 * == Usage ==
 *
 * {{{
 * import cats.effect.{IOApp, IO, ExitCode}
 * import io.github.arashi01.emile.cats.LibuvPollingSystem
 *
 * object MyApp extends IOApp:
 *   override protected def pollingSystem = LibuvPollingSystem.default
 *
 *   def run(args: List[String]): IO[ExitCode] = ...
 * }}}
 *
 * Or use the provided `EmileIOApp` trait:
 *
 * {{{
 * import io.github.arashi01.emile.cats.EmileIOApp
 *
 * object MyApp extends EmileIOApp:
 *   override def loopConfig: LoopConfig = LoopConfig.withMetrics
 *   def run(args: List[String]): IO[ExitCode] = ...
 * }}}
 *
 * == Thread Model ==
 *
 * - Each worker thread has its own libuv loop (via `LibuvPoller`)
 * - Handles created on one loop CANNOT be used on another
 * - Use `EmileIOApp.withLoop` to access the current thread's loop
 */
final class LibuvPollingSystem private (config: LoopConfig) extends PollingSystem:
  /** The API exposed to IO effects - provides access to the libuv loop. */
  type Api = LibuvPollingSystem.LoopAccess

  /** The per-thread poller wrapping a libuv loop. */
  type Poller = LibuvPollingSystem.LibuvPoller

  override def close(): Unit = ()
    // Nothing to do at system level - pollers are closed individually

  override def makeApi(ctx: PollingContext[Poller]): LibuvPollingSystem.LoopAccess =
    new LibuvPollingSystem.LoopAccess(ctx)

  override def makePoller(): Poller =
    EmilePoller(config) match
      case Right(p) => new LibuvPollingSystem.LibuvPoller(p)
      case Left(e)  => throw e // scalafix:ok; cats-effect PollingSystem.makePoller API requires throwing

  override def closePoller(poller: Poller): Unit =
    poller.underlying.close()

  override def poll(poller: Poller, nanos: Long): CatsPollResult =
    poller.underlying.poll(nanos) match
      case PollResult.Complete    => CatsPollResult.Complete
      case PollResult.Idle        => CatsPollResult.Complete // cats-effect has no Idle; loop liveness is signalled via needsPoll
      case PollResult.Interrupted => CatsPollResult.Interrupted

  override def processReadyEvents(poller: Poller): Boolean =
    poller.underlying.processReadyEvents()

  override def needsPoll(poller: Poller): Boolean =
    poller.underlying.needsPoll

  override def interrupt(targetThread: Thread, targetPoller: Poller): Unit =
    targetPoller.underlying.interrupt()

  override def metrics(poller: Poller): PollerMetrics =
    poller.metrics

  /** Expose the captured configuration for inspection/testing. */
  def loopConfig: LoopConfig = config
end LibuvPollingSystem

object LibuvPollingSystem:
  /** Default polling system with no loop overrides. */
  val default: LibuvPollingSystem = LibuvPollingSystem(LoopConfig.empty)

  /** Builder for a polling system with the provided loop configuration. */
  def apply(config: LoopConfig): LibuvPollingSystem = new LibuvPollingSystem(config)

  // =========================================================================
  // Nested types
  // =========================================================================

  /**
   * API for accessing the libuv loop from IO effects.
   *
   * Provides safe access to the current worker thread's loop.
   * Similar to `FileDescriptorPoller` for epoll/kqueue, but for libuv.
   */
  final class LoopAccess private[LibuvPollingSystem] (ctx: PollingContext[LibuvPoller]):
    /**
     * Execute a callback with access to the current thread's libuv loop.
     *
     * The callback will be executed on a worker thread that owns the loop.
     * This is the safe way to create libuv handles from within IO effects.
     *
     * @param f Callback that receives the loop
     */
    def withLoop(f: Loop => Unit): Unit =
      ctx.accessPoller(poller => f(poller.loop))

    /**
     * Get the current thread's loop, wrapped in a Resource that manages lifecycle.
     *
     * This is the recommended way to get a loop for creating libuv handles.
     * The loop is NOT closed when the resource is released (it belongs to the runtime).
     *
     * @return Resource providing access to the loop with typed error channel
     */
    def loop: Resource[Eff.Of[IO, EmileError], Loop] =
      Resource.eval(IO.async_[Loop] { cb =>
        ctx.accessPoller(poller => cb(Right(poller.loop)))
      }).eff[EmileError]

    /**
     * Check if the current thread owns the given poller.
     *
     * @param poller The poller to check
     * @return true if safe to interact with this poller
     */
    def ownsPoller(poller: LibuvPoller): Boolean =
      ctx.ownPoller(poller)

    /**
     * Check if the current worker owns the supplied loop.
     *
     * @return Eff with typed error channel indicating ownership
     */
    def ownsLoop(loop: Loop): Eff[IO, EmileError, Boolean] =
      Eff.liftF[IO, EmileError, Boolean](IO.async_[Boolean] { cb =>
        ctx.accessPoller { poller =>
          cb(Right(poller.loop == loop))
        }
      })

  object LoopAccess:
    /**
     * Get the LoopAccess API with typed error channel.
     *
     * @return Eff containing LoopAccess or EmileError.MissingLibuvPollingSystem
     */
    def get: Eff[IO, EmileError, LoopAccess] =
      Eff.liftF[IO, EmileError, Option[LoopAccess]](
        IO.pollers.map(_.collectFirst { case access: LoopAccess => access })
      ).flatMap {
        case Some(access) => Eff.succeed[IO, EmileError, LoopAccess](access)
        case None         => Eff.fail[IO, EmileError, LoopAccess](EmileError.MissingLibuvPollingSystem)
      }

  /**
   * Per-thread poller instance wrapping a libuv Poller.
   */
  final class LibuvPoller private[LibuvPollingSystem] (
      private[LibuvPollingSystem] val underlying: EmilePoller
  ):
    /** The libuv loop for this poller. */
    def loop: Loop = underlying.loop

    /** Metrics for this poller. */
    private[LibuvPollingSystem] val metrics: PollerMetrics = new PollerMetrics:
      // libuv doesn't track individual operation counts like io_uring does.
      // We provide stub implementations. For production use, we could add
      // tracking in emile-core's callback registry.
      override def operationsOutstandingCount(): Int = 0
      override def totalOperationsSubmittedCount(): Long = 0L
      override def totalOperationsSucceededCount(): Long = 0L
      override def totalOperationsErroredCount(): Long = 0L
      override def totalOperationsCanceledCount(): Long = 0L
      override def acceptOperationsOutstandingCount(): Int = 0
      override def totalAcceptOperationsSubmittedCount(): Long = 0L
      override def totalAcceptOperationsSucceededCount(): Long = 0L
      override def totalAcceptOperationsErroredCount(): Long = 0L
      override def totalAcceptOperationsCanceledCount(): Long = 0L
      override def connectOperationsOutstandingCount(): Int = 0
      override def totalConnectOperationsSubmittedCount(): Long = 0L
      override def totalConnectOperationsSucceededCount(): Long = 0L
      override def totalConnectOperationsErroredCount(): Long = 0L
      override def totalConnectOperationsCanceledCount(): Long = 0L
      override def readOperationsOutstandingCount(): Int = 0
      override def totalReadOperationsSubmittedCount(): Long = 0L
      override def totalReadOperationsSucceededCount(): Long = 0L
      override def totalReadOperationsErroredCount(): Long = 0L
      override def totalReadOperationsCanceledCount(): Long = 0L
      override def writeOperationsOutstandingCount(): Int = 0
      override def totalWriteOperationsSubmittedCount(): Long = 0L
      override def totalWriteOperationsSucceededCount(): Long = 0L
      override def totalWriteOperationsErroredCount(): Long = 0L
      override def totalWriteOperationsCanceledCount(): Long = 0L
end LibuvPollingSystem
