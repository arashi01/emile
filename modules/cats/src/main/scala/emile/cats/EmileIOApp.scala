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
import cats.effect.IOApp
import cats.effect.Resource
import cats.effect.unsafe.PollingSystem

import boilerplate.effect.Eff

import emile.EmileError
import emile.Loop
import emile.LoopConfig

/**
 * IOApp trait that uses libuv as the polling backend.
 *
 * This is the recommended way to build cats-effect applications with emile.
 * It configures the cats-effect runtime to use libuv for all I/O polling,
 * eliminating the need for separate event loop management.
 *
 * == Usage ==
 *
 * {{{
 * import cats.effect.{IO, ExitCode}
 * import emile.cats.EmileIOApp
 * import emile.Tcp
 *
 * object MyServer extends EmileIOApp:
 *   def run(args: List[String]): IO[ExitCode] =
 *     for
 *       // Access the libuv loop for the current worker thread
 *       _ <- IO.println("Starting server...")
 *       // Use emile APIs that work with the integrated loop
 *       _ <- ???
 *     yield ExitCode.Success
 * }}}
 *
 * == Loop Access ==
 *
 * Use `EmileIOApp.withLoop` to access the current thread's libuv loop:
 *
 * {{{
 * EmileIOApp.withLoop { loop =>
 *   // Create handles, start timers, etc.
 *   IO.fromEither(Tcp.init(loop))
  * {{
  * val createTcp: IO[Either[EmileError, Tcp[Open]]] =
  *   EmileIOApp.withLoop { loop => IO.pure(Tcp.init(loop)) }
  * }}}
 * object MyApp extends EmileIOApp:
 *   override def loopConfig: LoopConfig =
 *     LoopConfig.empty
 *       .withMetricsEnabled(true)
 *       .withBlockSignal(SIGPROF)
 *
 *   def run(args: List[String]): IO[ExitCode] = ...
 * }}}
 */
trait EmileIOApp extends IOApp:
  private lazy val emilePollingSystem: PollingSystem =
    LibuvPollingSystem(loopConfig)

  /**
   * Override to customize the libuv loop configuration.
   *
   * This is called once before the runtime starts.
   */
  def loopConfig: LoopConfig = LoopConfig.empty

  /**
   * The polling system used by this application.
   *
   * Uses libuv via `LibuvPollingSystem`.
   */
  override protected def pollingSystem: PollingSystem =
    emilePollingSystem

object EmileIOApp:
  /**
   * Execute a callback with access to the current worker thread's libuv loop.
   *
   * This must be called from within an IO effect running on the cats-effect runtime.
   * The callback receives the loop owned by the current worker thread.
   *
   * {{{
   * val createTcp: Eff[IO, EmileError, Tcp[Open]] =
   *   EmileIOApp.withLoop { loop =>
   *     Tcp.init(loop).eff[IO]
   *   }
   * }}}
   *
   * @param f Callback that receives the loop
   */
  def withLoop[A](f: Loop => Eff[IO, EmileError, A]): Eff[IO, EmileError, A] =
    loopResource.use(f)

  /**
   * Access the current worker loop as a Resource for compositional use.
   */
  def loopResource: Resource[Eff.Of[IO, EmileError], Loop] =
    Resource.eval(LibuvPollingSystem.LoopAccess.get).flatMap(_.loop)
