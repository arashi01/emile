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

import boilerplate.effect.*
import boilerplate.effect.Eff

import emile.Async
import emile.EmileError
import emile.Loop
import emile.Open

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
