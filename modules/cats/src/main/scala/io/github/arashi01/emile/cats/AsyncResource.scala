/*
 * Copyright 2025 the original author(s).
 * SPDX-License-Identifier: MIT
 */
package io.github.arashi01.emile.cats

import boilerplate.effect.*
import boilerplate.effect.Eff
import cats.effect.IO
import cats.effect.Resource
import io.github.arashi01.emile.Async
import io.github.arashi01.emile.EmileError
import io.github.arashi01.emile.Loop
import io.github.arashi01.emile.Open

/**
 * cats-effect Resource integration for Async handles.
 *
 * Async handles allow any thread to wake up the event loop and invoke
 * a callback on the loop thread. This is the primary mechanism for
 * thread-safe communication with the event loop.
 *
 * Requires an integrated loop (via EmileLoop.integrated) for proper async
 * callback processing during resource release.
 */
object AsyncResource:

  /** Helper to convert IO[Unit] finalizers to Eff context. */
  private inline def liftFinalizer(async: Async[Open]): Eff[IO, EmileError, Unit] =
    Eff.liftF[IO, EmileError, Unit](IO.async_ { cb =>
      async.closeAsync(_ => cb(Right(())))
    })

  /**
   * Create an async handle as a managed resource.
   *
   * The handle is closed asynchronously when the resource is released.
   * The finalizer awaits the close callback before returning.
   *
   * @param callback The callback to invoke when the async is signaled
   * @param loop The event loop (implicit) - should be an integrated loop
   * @return Resource that acquires and safely releases an async handle with typed error channel
   */
  def make(callback: () => Unit)(using loop: Loop): Resource[Eff.Of[IO, EmileError], Async[Open]] =
    Resource.make(
      acquire = LoopOwnership.ensureOwned(loop) *> Async.init(loop)(callback).eff[IO]
    )(
      release = liftFinalizer
    )
end AsyncResource
