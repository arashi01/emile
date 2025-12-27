/*
 * Copyright 2025 the original author(s).
 * SPDX-License-Identifier: MIT
 */
package io.github.arashi01.emile

import scala.annotation.targetName

/**
 * Timeout duration in milliseconds for timer operations.
 *
 * This is a zero-cost wrapper around Long for type safety. Named `Timeout`
 * to avoid collision with `scala.concurrent.duration.Duration`.
 */
opaque type Timeout = Long

object Timeout:
  given CanEqual[Timeout, Timeout] = CanEqual.derived
  given Ordering[Timeout] = Ordering.Long

  /** Construct timeout from milliseconds. */
  inline def millis(ms: Long): Timeout = ms

  /** Construct timeout from seconds. */
  inline def seconds(s: Long): Timeout = s * 1000L

  /** Construct timeout from minutes. */
  inline def minutes(m: Long): Timeout = m * 60000L

  /** Construct timeout from hours. */
  inline def hours(h: Long): Timeout = h * 3600000L

  /** Zero timeout constant. */
  val Zero: Timeout = 0L

  extension (t: Timeout)
    /** Get the timeout in milliseconds. */
    inline def toMillis: Long = t

    /** Get the timeout in seconds (truncated). */
    inline def toSeconds: Long = t / 1000L

    /** Add two timeouts. */
    @targetName("plus")
    inline def +(other: Timeout): Timeout = t + other

    /** Subtract a timeout. */
    @targetName("minus")
    inline def -(other: Timeout): Timeout = t - other

    /** Multiply by a factor. */
    @targetName("times")
    inline def *(factor: Long): Timeout = t * factor

    /** Divide by a divisor. */
    @targetName("div")
    inline def /(divisor: Long): Timeout = t / divisor

    /** Check if timeout is zero. */
    inline def isZero: Boolean = t == 0L

    /** Check if timeout is positive. */
    inline def isPositive: Boolean = t > 0L

    /** Check if timeout is negative. */
    inline def isNegative: Boolean = t < 0L
end Timeout
