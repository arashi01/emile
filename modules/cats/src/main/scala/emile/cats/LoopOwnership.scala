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

import boilerplate.effect.*
import boilerplate.effect.Eff

import emile.EmileError
import emile.Loop

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
