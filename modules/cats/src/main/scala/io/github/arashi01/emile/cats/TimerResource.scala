/*
 * Copyright 2025 the original author(s).
 * SPDX-License-Identifier: MIT
 */
package io.github.arashi01.emile.cats

import boilerplate.effect.*
import boilerplate.effect.Eff
import cats.effect.IO
import cats.effect.Resource
import io.github.arashi01.emile.EmileError
import io.github.arashi01.emile.Loop
import io.github.arashi01.emile.Open
import io.github.arashi01.emile.Timeout
import io.github.arashi01.emile.Timer

/**
 * cats-effect Resource integration for Timer handles.
 *
 * Provides managed resource acquisition and safe async cleanup for timers.
 * Requires an integrated loop (via EmileLoop.integrated) for proper async
 * callback processing during resource release.
 */
object TimerResource:

  /** Helper to convert IO[Unit] finalizers to Eff context. */
  private inline def liftFinalizer(timer: Timer[Open]): Eff[IO, EmileError, Unit] =
    Eff.liftF[IO, EmileError, Unit](IO.async_ { cb =>
      timer.closeAsync(_ => cb(Right(())))
    })

  /**
   * Create a timer handle as a managed resource.
   *
   * The handle is closed asynchronously when the resource is released.
   * The finalizer awaits the close callback before returning.
   *
   * @param loop The event loop (implicit) - should be an integrated loop
   * @return Resource that acquires and safely releases a timer with typed error channel
   */
  def make(using loop: Loop): Resource[Eff.Of[IO, EmileError], Timer[Open]] =
    Resource.make(
      acquire = LoopOwnership.ensureOwned(loop) *> Timer.init(loop).eff[IO]
    )(
      release = liftFinalizer
    )

  /**
   * Create a one-shot timer as a resource.
   *
   * The timer fires once after the specified timeout.
   *
   * @param timeout Time until the callback fires
   * @param callback The callback to invoke
   * @param loop The event loop (implicit)
   * @return Resource that acquires a started timer with typed error channel
   */
  def after(timeout: Timeout)(callback: () => Unit)(using loop: Loop): Resource[Eff.Of[IO, EmileError], Timer[Open]] =
    Resource.make(
      acquire = LoopOwnership.ensureOwned(loop) *> Timer.after(loop, timeout)(callback).eff[IO]
    )(
      release = liftFinalizer
    )

  /**
   * Create a repeating timer as a resource.
   *
   * The timer fires repeatedly at the specified interval.
   *
   * @param interval The repeat interval (also used for initial timeout)
   * @param callback The callback to invoke on each tick
   * @param loop The event loop (implicit)
   * @return Resource that acquires a started repeating timer with typed error channel
   */
  def interval(interval: Timeout)(callback: () => Unit)(using loop: Loop): Resource[Eff.Of[IO, EmileError], Timer[Open]] =
    Resource.make(
      acquire = LoopOwnership.ensureOwned(loop) *> Timer.interval(loop, interval)(callback).eff[IO]
    )(
      release = liftFinalizer
    )
end TimerResource
