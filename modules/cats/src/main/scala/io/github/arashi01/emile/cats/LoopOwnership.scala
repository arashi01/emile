/*
 * Copyright 2025 the original author(s).
 * SPDX-License-Identifier: MIT
 */
package io.github.arashi01.emile.cats

import boilerplate.effect.*
import boilerplate.effect.Eff
import cats.effect.IO
import io.github.arashi01.emile.EmileError
import io.github.arashi01.emile.Loop

/**
 * Validates that a libuv loop is owned by the current cats-effect worker.
 *
 * Resource helpers must only operate on loops that belong to the active
 * `LibuvPollingSystem` worker. This prevents cross-thread handle misuse and
 * protects async finalisers from hanging when the wrong loop is used.
 */
private[cats] object LoopOwnership:
  inline def ensureOwned(loop: Loop): Eff[IO, EmileError, Unit] =
    LibuvPollingSystem.LoopAccess.get.flatMap { access =>
      access.ownsLoop(loop).flatMap { owned =>
        if owned then Eff.succeed[IO, EmileError, Unit](())
        else Eff.fail[IO, EmileError, Unit](EmileError.LoopOwnershipViolation)
      }
    }
end LoopOwnership
