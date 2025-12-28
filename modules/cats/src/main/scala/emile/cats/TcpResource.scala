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
import boilerplate.nullable.*

import emile.EmileError
import emile.ErrorCode
import emile.Loop
import emile.Open
import emile.Tcp
import emile.TcpConfig
import emile.ipa.SocketAddress

/**
 * cats-effect Resource integration for TCP handles.
 *
 * Provides managed resource acquisition and safe async cleanup for TCP handles.
 */
object TcpResource:

  /** Helper to convert IO[Unit] finalizers to Eff context. */
  private inline def liftFinalizer(fin: Tcp[Open] => IO[Unit]): Tcp[Open] => Eff[IO, EmileError, Unit] =
    tcp => Eff.liftF[IO, EmileError, Unit](fin(tcp))

  /**
   * Create a TCP handle as a managed resource.
   *
   * The handle is closed asynchronously when the resource is released.
   * The finalizer awaits the close callback before returning.
   *
   * @param loop The event loop (implicit)
   * @return Resource that acquires and safely releases a TCP handle with typed error channel
   */
  def make(using loop: Loop): Resource[Eff.Of[IO, EmileError], Tcp[Open]] =
    Resource.make(
      acquire = acquireTcp(loop, None)
    )(
      release = liftFinalizer(closeAsyncIO)
    )

  /**
   * Create a TCP handle with configuration as a managed resource.
   *
   * @param config Configuration for the TCP handle
   * @param loop The event loop (implicit)
   * @return Resource that acquires and safely releases a TCP handle with typed error channel
   */
  def make(config: TcpConfig)(using loop: Loop): Resource[Eff.Of[IO, EmileError], Tcp[Open]] =
    Resource.make(
      acquire = acquireTcp(loop, Some(config))
    )(
      release = liftFinalizer(closeAsyncIO)
    )

  /**
   * Create a TCP server socket bound to an address.
   *
   * This is a convenience method combining handle creation and bind.
   *
   * @param address The address to bind to
   * @param loop The event loop (implicit)
   * @return Resource that acquires a bound TCP handle with typed error channel
   */
  def bind(address: SocketAddress)(using loop: Loop): Resource[Eff.Of[IO, EmileError], Tcp[Open]] =
    make.evalTap { tcp =>
      tcp.bind(address).eff[IO]
    }

  /**
   * Create a TCP server socket bound to an address with configuration.
   *
   * @param address The address to bind to
   * @param config Configuration for the TCP handle
   * @param loop The event loop (implicit)
   * @return Resource that acquires a bound TCP handle with typed error channel
   */
  def bind(address: SocketAddress, config: TcpConfig)(using loop: Loop): Resource[Eff.Of[IO, EmileError], Tcp[Open]] =
    make(config).evalTap { tcp =>
      tcp.bind(address).eff[IO]
    }

  /**
   * Create a TCP client and connect to a remote address.
   *
   * The connection is completed asynchronously. The resource is released
   * when the TCP handle is closed.
   *
   * @param address The remote address to connect to
   * @param loop The event loop (implicit)
   * @return Resource that acquires a connected TCP handle with typed error channel
   */
  def connect(address: SocketAddress)(using loop: Loop): Resource[Eff.Of[IO, EmileError], Tcp[Open]] =
    Resource.make(
      acquire = acquireTcp(loop, None).flatMap(tcp => connectEff(tcp, address))
    )(release = liftFinalizer(closeAsyncIO))

  /**
   * Create a TCP client with configuration and connect to a remote address.
   *
   * @param address The remote address to connect to
   * @param config Configuration for the TCP handle
   * @param loop The event loop (implicit)
   * @return Resource that acquires a connected TCP handle with typed error channel
   */
  def connect(address: SocketAddress, config: TcpConfig)(using loop: Loop): Resource[Eff.Of[IO, EmileError], Tcp[Open]] =
    Resource.make(
      acquire = acquireTcp(loop, Some(config)).flatMap(tcp => connectEff(tcp, address))
    )(release = liftFinalizer(closeAsyncIO))

  private inline def acquireTcp(loop: Loop, config: Option[TcpConfig]): Eff[IO, EmileError, Tcp[Open]] =
    LoopOwnership.ensureOwned(loop) *> config.fold(Tcp.init(loop))(Tcp.init(loop, _)).eff[IO]

  private def closeAsyncIO(tcp: Tcp[Open]): IO[Unit] =
    IO.async_ { cb =>
      tcp.closeAsync(_ => cb(Right(())))
    }

  /** Connect to address, returning result in Eff channel (typed errors). */
  private def connectEff(tcp: Tcp[Open], address: SocketAddress): Eff[IO, EmileError, Tcp[Open]] =
    Eff.attempt[IO, EmileError, Tcp[Open]](
      IO.async[Tcp[Open]] { cb =>
        tcp.connect(address) { status =>
          if status >= 0 then cb(Right(tcp))
          else cb(Left(EmileError.fromErrorCode(ErrorCode(status))))
        } match
          case Right(_) => IO.pure(Some(closeAsyncIO(tcp)))
          case Left(e) =>
            tcp.closeAsync(_ => ()): Unit
            cb(Left(e))
            IO.pure(None)
      }.onError { case _ => closeAsyncIO(tcp) },
      {
        case e: EmileError => e
        case t => EmileError.SystemError(ErrorCode(-1), t.getMessage.option.getOrElse("Unknown error"))
      }
    )
end TcpResource
