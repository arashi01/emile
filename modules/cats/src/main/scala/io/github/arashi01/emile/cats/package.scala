/*
 * Copyright 2025 the original author(s).
 * SPDX-License-Identifier: MIT
 */
package io.github.arashi01.emile

/**
 * Re-exports from emile-core and emile-ipa for emile-cats users.
 *
 * This allows users to import all necessary types from the cats package
 * without needing separate imports from emile-core or emile-ipa:
 *
 * {{{
 * import io.github.arashi01.emile.cats.*
 *
 * // Now Loop, Tcp, Timer, SocketAddress, Port, etc. are available
 * }}}
 *
 * Uses Scala 3 `export` clauses for zero-overhead re-exports.
 */
package object cats:

  // =========================================================================
  // Core Handle Types and Companions
  // =========================================================================

  export io.github.arashi01.emile.{
    Loop,
    Tcp,
    Timer,
    Async,
    Poll,
    Signal,
    Dns
  }

  // =========================================================================
  // Handle State Types
  // =========================================================================

  export io.github.arashi01.emile.{
    HandleState,
    Open,
    Closed
  }

  // =========================================================================
  // Configuration Types
  // =========================================================================

  export io.github.arashi01.emile.{
    LoopConfig,
    TcpConfig,
    TcpKeepAlive,
    Timeout
  }

  // =========================================================================
  // Error Types
  // =========================================================================

  export io.github.arashi01.emile.{
    EmileError,
    ErrorCode
  }

  // =========================================================================
  // IPA Types (Addresses)
  // =========================================================================

  export io.github.arashi01.emile.ipa.{
    SocketAddress,
    Ipv4Address,
    Ipv6Address,
    Port,
    AddressError
  }

  // =========================================================================
  // IPA Literals
  // =========================================================================

  /** Literal interpolators for compile-time validated addresses and ports. */
  val literals: io.github.arashi01.emile.ipa.literals.type = io.github.arashi01.emile.ipa.literals

end cats

