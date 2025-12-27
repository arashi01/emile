/*
 * Copyright 2025 the original author(s).
 * SPDX-License-Identifier: MIT
 */
package io.github.arashi01.emile.cats

/**
 * Convenience syntax for emile-cats module.
 *
 * Import this object to get all emile-cats extensions and utilities:
 * {{{
 * import io.github.arashi01.emile.cats.syntax.all.*
 * }}}
 */
object syntax:
  /**
   * All emile-cats syntax.
   *
   * Exports:
   * - Eff conversion: .rethrow (Eff → IO raises)
   * - IO error handling: .catchEmile, .recoverEmile
   * - Emile pattern extractor for error handlers
   * - EmileLoop extensions (.runOnce, .runUntilComplete, .runNoWait)
   */
  object all:
    export io.github.arashi01.emile.cats.{rethrow, catchEmile, recoverEmile, Emile}
    export io.github.arashi01.emile.cats.EmileLoop.{runOnce, runUntilComplete, runNoWait}
end syntax
