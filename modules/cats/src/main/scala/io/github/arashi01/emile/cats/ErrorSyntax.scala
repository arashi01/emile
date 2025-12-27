/*
 * Copyright 2025 the original author(s).
 * SPDX-License-Identifier: MIT
 */
package io.github.arashi01.emile.cats

import boilerplate.effect.*
import boilerplate.effect.Eff
import cats.data.NonEmptyChain
import cats.data.Validated
import cats.data.ValidatedNec
import cats.effect.IO
import io.github.arashi01.emile.EmileError

/**
 * Error handling syntax for Eff-based typed error channels.
 *
 * Provides conversions between Eff[IO, EmileError, A] and IO[A], plus utilities
 * for working with EmileError in IO contexts.
 */

extension [A](eff: Eff[IO, EmileError, A])
  /**
   * Raise EmileError into IO's Throwable channel.
   *
   * Converts Eff[IO, EmileError, A] → IO[A], raising EmileError as throwables.
   * Since EmileError extends Throwable, this is zero-cost.
   */
  inline def rethrow: IO[A] = eff.either.flatMap {
    case Right(a) => IO.pure(a)
    case Left(e)  => IO.raiseError(e)
  }

extension [A](io: IO[A])
  /**
   * Recover from EmileError using partial function.
   *
   * @param pf Partial function from EmileError to recovery action
   */
  inline def catchEmile(pf: PartialFunction[EmileError, IO[A]]): IO[A] =
    io.handleErrorWith {
      case e: EmileError if pf.isDefinedAt(e) => pf(e)
      case other                              => IO.raiseError(other)
    }

  /**
   * Handle specific EmileError variants.
   *
   * @param f Function from EmileError to recovery value
   */
  inline def recoverEmile(f: EmileError => A): IO[A] =
    io.handleErrorWith {
      case e: EmileError => IO.pure(f(e))
      case other         => IO.raiseError(other)
    }

/**
 * Pattern extractor for EmileError in error handlers.
 *
 * Usage:
 * {{{
 * io.handleErrorWith {
 *   case Emile(EmileError.SystemError(code, _)) => fallback
 *   case other => IO.raiseError(other)
 * }
 * }}}
 */
object Emile:
  inline def unapply(t: Throwable): Option[EmileError] = t match
    case e: EmileError => Some(e)
    case _             => None

// ============================================================================
// Validation helpers
// ============================================================================

extension (errors: List[EmileError])
  /** Convert a list of errors into a ValidatedNec accumulator. */
  inline def toValidatedNec: ValidatedNec[EmileError, Unit] =
    NonEmptyChain.fromSeq(errors) match
      case Some(nec) => Validated.invalid(nec)
      case None      => Validated.valid(())

extension [A](either: Either[List[EmileError], A])
  /** Convert Either[List[EmileError], A] into ValidatedNec[EmileError, A]. */
  inline def toValidatedNec: ValidatedNec[EmileError, A] =
    either match
      case Right(value) => Validated.validNec(value)
      case Left(errs) =>
        NonEmptyChain.fromSeq(errs) match
          case Some(nec) => Validated.invalid(nec)
          case None =>
            Validated.invalidNec(
              EmileError.InvalidArgument("errors", "Empty error list supplied to toValidatedNec.")
            )
