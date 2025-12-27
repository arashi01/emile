/*
 * Copyright 2025 the original author(s).
 * SPDX-License-Identifier: MIT
 */
package io.github.arashi01.emile.cats

import boilerplate.effect.*
import cats.effect.IO
import io.github.arashi01.emile.Dns
import io.github.arashi01.emile.EmileError
import io.github.arashi01.emile.Loop
import io.github.arashi01.emile.ipa.SocketAddress

/**
 * cats-effect integration for DNS resolution.
 *
 * Provides async DNS resolution using libuv's uv_getaddrinfo, wrapped in
 * the Eff typed error channel pattern.
 *
 * == Example ==
 * {{{
 * EmileLoop.integrated.use { implicit loop =>
 *   for
 *     addresses <- DnsResolver.resolve("example.com", "80")
 *     _ <- addresses.traverse_ { addr =>
 *       IO.println(s"Resolved: $addr")
 *     }.eff
 *   yield ()
 * }
 * }}}
 */
object DnsResolver:

  /**
   * Resolve a hostname to a list of socket addresses.
   *
   * This is an async operation that uses libuv's uv_getaddrinfo under the hood.
   * The resolution may involve network I/O and could take significant time.
   *
   * @param node The hostname to resolve (e.g., "example.com", "localhost")
   * @param service The service name or port number (e.g., "http", "80", "443")
   * @param loop The event loop (implicit)
   * @return Eff containing the list of resolved socket addresses
   */
  def resolve(node: String, service: String)(using loop: Loop): Eff[IO, EmileError, List[SocketAddress]] =
    LoopOwnership.ensureOwned(loop) *> Eff.lift[IO, EmileError, List[SocketAddress]](
      IO.async[Either[EmileError, List[SocketAddress]]] { cb =>
        IO {
          Dns.getAddrInfo(loop, node, service) { result =>
            cb(Right(result))
          } match
            case Right(_) =>
              // Request submitted successfully, cancellation not supported
              None
            case Left(err) =>
              // Request initiation failed
              cb(Right(Left(err)))
              None
        }
      }.map(identity)
    )

  /**
   * Resolve a hostname with address family and socket type hints.
   *
   * Allows specifying address family hints to control the types of addresses
   * returned (IPv4 only, IPv6 only, etc.) and socket type (TCP/UDP).
   *
   * @param node The hostname to resolve
   * @param service The service name or port number
   * @param family Address family hint (use Dns.AF_INET, Dns.AF_INET6, or Dns.AF_UNSPEC)
   * @param socktype Socket type hint (use Dns.SOCK_STREAM for TCP, Dns.SOCK_DGRAM for UDP)
   * @param loop The event loop (implicit)
   * @return Eff containing the list of resolved socket addresses
   */
  def resolveWithHints(node: String, service: String, family: Int, socktype: Int)(using loop: Loop): Eff[IO, EmileError, List[SocketAddress]] =
    LoopOwnership.ensureOwned(loop) *> Eff.lift[IO, EmileError, List[SocketAddress]](
      IO.async[Either[EmileError, List[SocketAddress]]] { cb =>
        IO {
          Dns.getAddrInfoWithHints(loop, node, service, family, socktype) { result =>
            cb(Right(result))
          } match
            case Right(_) =>
              None
            case Left(e) =>
              cb(Right(Left(e)))
              None
        }
      }.map(identity)
    )

  /**
   * Resolve a hostname with port specified as integer.
   *
   * Convenience overload that converts the port to a service string.
   *
   * @param node The hostname to resolve
   * @param port The port number
   * @param loop The event loop (implicit)
   * @return Eff containing the list of resolved socket addresses
   */
  def resolve(node: String, port: Int)(using loop: Loop): Eff[IO, EmileError, List[SocketAddress]] =
    resolve(node, port.toString)

  /**
   * Resolve a hostname without service/port specification.
   *
   * Returns addresses without port information (port 0).
   *
   * @param node The hostname to resolve
   * @param loop The event loop (implicit)
   * @return Eff containing the list of resolved socket addresses
   */
  def resolve(node: String)(using loop: Loop): Eff[IO, EmileError, List[SocketAddress]] =
    resolve(node, "")
end DnsResolver
