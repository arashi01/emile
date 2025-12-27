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
import io.github.arashi01.emile.Poll
import io.github.arashi01.emile.PollEvent

import scala.scalanative.unsafe.Ptr

/**
 * cats-effect Resource integration for Poll handles.
 *
 * Poll handles are used to watch file descriptors for readability,
 * writability and disconnection, similar to POSIX `poll(2)`.
 */
object PollResource:

  /** Helper to convert IO[Unit] finalizers to Eff context. */
  private inline def liftFinalizer(poll: Poll[Open]): Eff[IO, EmileError, Unit] =
    Eff.liftF[IO, EmileError, Unit](IO.async_ { cb =>
      poll.closeAsync(_ => cb(Right(())))
    })

  /**
   * Create a poll handle as a managed resource.
   *
   * The handle is closed asynchronously when the resource is released.
   * The finalizer awaits the close callback before returning.
   *
   * @param fd The file descriptor to poll
   * @param loop The event loop (implicit)
   * @return Resource that acquires and safely releases a poll handle with typed error channel
   */
  def make(fd: Int)(using loop: Loop): Resource[Eff.Of[IO, EmileError], Poll[Open]] =
    Resource.make(
      acquire = LoopOwnership.ensureOwned(loop) *> Poll.init(loop, fd).eff[IO]
    )(
      release = liftFinalizer
    )

  /**
   * Create a poll handle for a socket as a managed resource.
   *
   * On Unix this is identical to `make`. On Windows this accepts
   * a SOCKET handle instead of a file descriptor.
   *
   * @param socket The socket to poll (platform-specific type)
   * @param loop The event loop (implicit)
   * @return Resource that acquires and safely releases a poll handle with typed error channel
   */
  def makeSocket(socket: Ptr[Byte])(using loop: Loop): Resource[Eff.Of[IO, EmileError], Poll[Open]] =
    Resource.make(
      acquire = LoopOwnership.ensureOwned(loop) *> Poll.initSocket(loop, socket).eff[IO]
    )(
      release = liftFinalizer
    )

  /**
   * Create a poll handle and start polling for events.
   *
   * This is a convenience method combining resource allocation and poll start.
   *
   * @param fd The file descriptor to poll
   * @param events The events to poll for
   * @param callback Callback invoked when events are detected
   * @param loop The event loop (implicit)
   * @return Resource that acquires a started poll handle with typed error channel
   */
  def started(fd: Int, events: PollEvent*)(callback: (Int, Set[PollEvent]) => Unit)(using loop: Loop): Resource[Eff.Of[IO, EmileError], Poll[Open]] =
    make(fd).evalTap { poll =>
      poll.start(events*)(callback).eff[IO]
    }
end PollResource
