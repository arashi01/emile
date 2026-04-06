/*
 * Copyright 2025, 2026 Ali Rashid.
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
package emile

import scala.scalanative.unsafe.*

import boilerplate.*

/** libuv error code, representing negated errno on Unix or libuv-defined codes.
  *
  * Error codes are negative integers in libuv. Positive values indicate success or the number of
  * bytes transferred.
  */
opaque type ErrorCode = Int

object ErrorCode extends OpaqueType[ErrorCode, Int], OpaqueType.Eq[ErrorCode]:
  type Error = Nothing

  inline def wrap(value: Int): ErrorCode = value
  inline def unwrap(value: ErrorCode): Int = value
  protected inline def validate(value: Int): Option[Nothing] = None
  inline def apply(inline value: Int): ErrorCode = value

  extension (c: ErrorCode)
    inline def value: Int = c
    inline def isSuccess: Boolean = c >= 0
    inline def isError: Boolean = c < 0
    inline def isEof: Boolean = c == Eof.value

  // =========================================================================
  // libuv error codes derived from the C macros at link time.
  //
  // On POSIX these are negated system errno values (e.g. UV_ENOSYS = -38
  // on Linux). On Windows they are fixed constants (e.g. UV_ENOSYS = -4054).
  // The C helper emile_uv_errno.c exposes the actual macro values as
  // functions, making them available to Scala Native on every platform.
  // =========================================================================

  lazy val Eof: ErrorCode = UvErrno.emile_uv_eof()
  lazy val Cancelled: ErrorCode = UvErrno.emile_uv_ecanceled()
  lazy val ConnectionRefused: ErrorCode = UvErrno.emile_uv_econnrefused()
  lazy val ConnectionReset: ErrorCode = UvErrno.emile_uv_econnreset()
  lazy val AddressInUse: ErrorCode = UvErrno.emile_uv_eaddrinuse()
  lazy val AddressNotAvailable: ErrorCode = UvErrno.emile_uv_eaddrnotavail()
  lazy val TimedOut: ErrorCode = UvErrno.emile_uv_etimedout()
  lazy val InvalidArgument: ErrorCode = UvErrno.emile_uv_einval()
  lazy val BadFileDescriptor: ErrorCode = UvErrno.emile_uv_ebadf()
  lazy val PermissionDenied: ErrorCode = UvErrno.emile_uv_eacces()
  lazy val NetworkUnreachable: ErrorCode = UvErrno.emile_uv_enetunreach()
  lazy val HostUnreachable: ErrorCode = UvErrno.emile_uv_ehostunreach()
  lazy val BrokenPipe: ErrorCode = UvErrno.emile_uv_epipe()
  lazy val Again: ErrorCode = UvErrno.emile_uv_eagain()
  lazy val AlreadyConnected: ErrorCode = UvErrno.emile_uv_eisconn()
  lazy val NotConnected: ErrorCode = UvErrno.emile_uv_enotconn()
  lazy val ConnectionAborted: ErrorCode = UvErrno.emile_uv_econnaborted()
  lazy val NoMemory: ErrorCode = UvErrno.emile_uv_enomem()
  lazy val Busy: ErrorCode = UvErrno.emile_uv_ebusy()
  lazy val NoSys: ErrorCode = UvErrno.emile_uv_enosys()
  lazy val NotSupported: ErrorCode = UvErrno.emile_uv_enotsup()
end ErrorCode

/** C helper exposing libuv UV_E* error code macros as callable functions. */
@extern
private[emile] object UvErrno:
  def emile_uv_eof(): CInt = extern
  def emile_uv_ecanceled(): CInt = extern
  def emile_uv_econnrefused(): CInt = extern
  def emile_uv_econnreset(): CInt = extern
  def emile_uv_eaddrinuse(): CInt = extern
  def emile_uv_eaddrnotavail(): CInt = extern
  def emile_uv_etimedout(): CInt = extern
  def emile_uv_einval(): CInt = extern
  def emile_uv_ebadf(): CInt = extern
  def emile_uv_eacces(): CInt = extern
  def emile_uv_enetunreach(): CInt = extern
  def emile_uv_ehostunreach(): CInt = extern
  def emile_uv_epipe(): CInt = extern
  def emile_uv_eagain(): CInt = extern
  def emile_uv_eisconn(): CInt = extern
  def emile_uv_enotconn(): CInt = extern
  def emile_uv_econnaborted(): CInt = extern
  def emile_uv_enomem(): CInt = extern
  def emile_uv_ebusy(): CInt = extern
  def emile_uv_enosys(): CInt = extern
  def emile_uv_enotsup(): CInt = extern
end UvErrno
