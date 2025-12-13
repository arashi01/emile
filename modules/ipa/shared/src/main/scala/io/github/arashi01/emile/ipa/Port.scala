/*
 * Copyright 2025 the original author(s).
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://opensource.org/licenses/MIT
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.arashi01.emile.ipa

import scala.compiletime.error

/**
 * A TCP or UDP port number in the range [0, 65535].
 *
 * This is a zero-cost opaque type wrapping `Int`. Port values are validated at
 * compile time when using literal values, or at runtime when parsing from
 * strings or integers.
 *
 * == Construction ==
 *
 * {{{
 * // Compile-time validated literal (preferred)
 * val http = Port(80)
 * val https = Port(443)
 *
 * // Runtime validation
 * val dynamic: Either[AddressError, Port] = Port.from(userInput)
 * val parsed: Either[AddressError, Port] = Port.from("8080")
 *
 * // Unchecked (caller must ensure validity)
 * val unsafe = Port.unsafeFromInt(value)
 * }}}
 *
 * == Well-known Ports ==
 *
 * {{{
 * Port.Wildcard  // 0 - lets the OS choose an ephemeral port
 * }}}
 *
 * @see
 *   [[https://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.xhtml IANA Port Numbers]]
 */
opaque type Port = Int

object Port:
  given CanEqual[Port, Port] = CanEqual.derived
  given Ordering[Port]       = Ordering.Int

  /** Minimum valid port value. */
  inline val MinValue = 0

  /** Maximum valid port value. */
  inline val MaxValue = 65535

  /** Wildcard port (0) - lets the OS choose an ephemeral port. */
  val Wildcard: Port = 0

  /**
   * Construct a Port from a literal integer with compile-time validation.
   *
   * This method uses Scala 3's inline feature to validate the port value at
   * compile time. If the value is not a literal or is out of range, a
   * compile-time error is raised.
   *
   * @param value
   *   The port number (must be a literal in range 0-65535)
   * @return
   *   The validated Port
   */
  inline def apply(inline value: Int): Port =
    inline if value < MinValue || value > MaxValue then
      error("Port must be in range 0-65535")
    else value

  /**
   * Construct a Port from a runtime integer with validation.
   */
  def from(value: Int): Either[AddressError, Port] =
    if value >= MinValue && value <= MaxValue then Right(value)
    else Left(AddressError.InvalidPort(value))

  /**
   * Parse a Port from a string representation with validation and error
   * detail.
   */
  def from(value: String): Either[AddressError, Port] =
    if value == null then Left(AddressError.InvalidPortString("null", "null input"))
    else
      val trimmed = value.trim
      if trimmed.isEmpty then Left(AddressError.InvalidPortString(value, "empty input"))
      else
        scala.util.Try(trimmed.toInt).toOption match
          case Some(n) => from(n)
          case None    => Left(AddressError.InvalidPortString(value, "non-numeric input"))

  /**
   * Construct a Port from an integer without validation.
   *
   * WARNING: Caller must ensure value is in valid range [0, 65535]. Using
   * invalid values leads to undefined behaviour.
   */
  inline def unsafeFromInt(value: Int): Port = value

  extension (p: Port)
    /** Get the underlying integer value. */
    inline def value: Int = p

    /** Append decimal representation to an Appendable. */
    def writeTo[A <: Appendable](out: A): A =
      out.append(java.lang.Integer.toString(p))
      out

    /** String representation. */
    def show: String =
      val sb = new java.lang.StringBuilder
      writeTo(sb): Unit
      sb.toString

end Port
