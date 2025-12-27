# Émile

> Named after the famed telegraph engineer, Émile provides ultra-low overhead Scala 3 Native bindings for libuv with first-class cats-effect integration.

[![Scala 3.7+](https://img.shields.io/badge/scala-3.7+-red.svg)](https://www.scala-lang.org/)
[![Scala Native 0.5+](https://img.shields.io/badge/scala--native-0.5+-blue.svg)](https://scala-native.org/)
[![cats-effect 3.7+](https://img.shields.io/badge/cats--effect-3.7+-green.svg)](https://typelevel.org/cats-effect/)

## Overview

Émile is designed as the I/O foundation for high-performance Scala Native applications, particularly the [Jenga](https://github.com/arashi01/jenga) HTTP/2 server framework.

### Key Features

- **Zero-overhead FFI**: Opaque types wrap native pointers with no runtime cost
- **Phantom-type safety**: Handle lifecycle tracked at compile time (`Open`/`Closed`)
- **Typed error channels**: `Eff[IO, EmileError, A]` for explicit error handling
- **cats-effect integration**: libuv becomes THE runtime poller via `LibuvPollingSystem`
- **Cross-platform addresses**: `emile-ipa` works on JVM, JS, and Native

## Architecture

```
┌──────────────┐    ┌───────────────┐    ┌───────────────┐
│  emile-ipa   │───►│  emile-core   │───►│  emile-cats   │
│ (addresses)  │    │ (libuv FFI)   │    │ (cats-effect) │
└──────────────┘    └───────────────┘    └───────────────┘
   JVM/JS/Native         Native              Native
```

| Module | Platform | Description |
|--------|----------|-------------|
| **emile-ipa** | JVM/JS/Native | IP addresses, ports, socket addresses with compile-time literals |
| **emile-core** | Native | libuv bindings: `Loop`, `Tcp`, `Timer`, `Async`, `Poll`, `Signal`, `Dns` handles |
| **emile-cats** | Native | cats-effect integration with `Resource`, `IO`, `Eff`, `DnsResolver`, `SignalStream` |

## Quick Start

### Installation

```scala
// build.sbt
libraryDependencies ++= Seq(
  "io.github.arashi01" %%% "emile-ipa"  % "0.1.0",  // Cross-platform
  "io.github.arashi01" %%  "emile-cats" % "0.1.0"   // Native only
)
```

### Hello World TCP Server

```scala
import cats.effect.*
import io.github.arashi01.emile.*
import io.github.arashi01.emile.cats.*
import io.github.arashi01.emile.ipa.*
import io.github.arashi01.emile.ipa.literals.*

object HelloServer extends EmileIOApp:
  def run(args: List[String]): IO[ExitCode] =
    EmileIOApp.withLoop { loop =>
      given Loop = loop
      
      val address = SocketAddress.v4(ipv4"0.0.0.0", port"8080")
      
      TcpResource.bind(address).use { server =>
        // Server logic here
        Eff.succeed[IO, EmileError, ExitCode](ExitCode.Success)
      }
    }.either.flatMap {
      case Right(code) => IO.pure(code)
      case Left(err)   => IO.println(s"Error: $err").as(ExitCode.Error)
    }
```

## Module Guide

### emile-ipa

Zero-allocation IP and socket address types with compile-time validation.

```scala
import io.github.arashi01.emile.ipa.*
import io.github.arashi01.emile.ipa.literals.*

// Compile-time validated literals
val addr = ipv4"192.168.1.1"
val p = port"8080"
val v6 = ipv6"::1"

// Runtime parsing with typed errors
val parsed: Either[AddressError, SocketAddress] = 
  SocketAddress.from("[::1]:443")

// IPv6 with scope ID
val scoped = SocketAddress.from("[fe80::1%eth0]:8080")

// Socket address construction
val socket = SocketAddress.v4(Ipv4Address.Loopback, Port(8080))
```

**Types**:
- `Port` - TCP/UDP port (0-65535)
- `Ipv4Address` - IPv4 address as 32-bit int
- `Ipv6Address` - IPv6 address as two 64-bit longs
- `SocketAddress` - V4 or V6 with port, flow info, scope ID

### emile-core

Direct libuv bindings with phantom-state tracking.

```scala
import io.github.arashi01.emile.*

// Create and run an event loop
for
  loop   <- Loop.create
  result <- loop.run(RunMode.Default)
  _      <- loop.close
yield result

// TCP server
for
  tcp  <- Tcp.init(loop)
  _    <- tcp.bind(address)
  _    <- tcp.listen(128) { status =>
            if status >= 0 then
              for client <- Tcp.init(loop); _ <- tcp.accept(client)
              yield client.readStart(data => println(s"Received: $data"))
          }
yield tcp
```

**Handle Types**:
- `Loop` - Event loop lifecycle
- `Tcp[S]` - TCP server/client
- `Timer[S]` - One-shot and repeating timers
- `Async[S]` - Cross-thread wakeup
- `Poll[S]` - File descriptor polling

### emile-cats

cats-effect integration with typed error channels.

```scala
import cats.effect.*
import boilerplate.effect.*
import io.github.arashi01.emile.cats.*

object MyApp extends EmileIOApp:
  override def loopConfig: LoopConfig = 
    LoopConfig.empty.withMetricsEnabled(true)
  
  def run(args: List[String]): IO[ExitCode] =
    EmileIOApp.withLoop { loop =>
      given Loop = loop
      
      // Resources with typed error channel
      TcpResource.bind(address).use { tcp =>
        TimerResource.delay(1000).use { _ =>
          Eff.succeed(ExitCode.Success)
        }
      }
    }.either.map(_.getOrElse(ExitCode.Error))
```

**Resource Types**:
- `TcpResource` - Managed TCP handles
- `TimerResource` - Managed timers with delay/interval
- `AsyncResource` - Managed async handles
- `PollResource` - Managed poll handles

## Error Handling

Émile uses typed error channels via `boilerplate.effect.Eff`:

```scala
// Eff[F, E, A] ≡ F[Either[E, A]]
type EffIO[A] = Eff[IO, EmileError, A]

// Constructing Eff values
val ok: EffIO[Int] = Eff.succeed[IO, EmileError, Int](42)
val err: EffIO[Int] = Eff.fail[IO, EmileError, Int](EmileError.TimedOut)

// Converting Either to Eff
val tcp: EffIO[Tcp[Open]] = Tcp.init(loop).eff[IO]

// Unwrapping to IO[Either[E, A]]
val io: IO[Either[EmileError, Tcp[Open]]] = tcp.either
```

**Error Types**:
- `AddressError` - Address parsing/validation (emile-ipa)
- `EmileError` - libuv operations, I/O errors (emile-core/cats)

## Requirements

- **Scala**: 3.7+
- **Scala Native**: 0.5+
- **libuv**: 1.x (system library)

### Installing libuv

```bash
# macOS
brew install libuv

# Ubuntu/Debian
apt install libuv1-dev

# Fedora/RHEL
dnf install libuv-devel

# Arch
pacman -S libuv
```

## Building

```bash
# Compile all modules
sbt compile

# Run tests
sbt test

# Code style check
sbt staticCheck

# Auto-format
sbt format
```

## Design Principles

1. **Errors as values**: `Either[E, A]` everywhere, never throw
2. **Zero-cost abstractions**: Opaque types, inline methods
3. **Compile-time safety**: Phantom types, Scala 3 features
4. **British English**: `initialise`, `colour`, `behaviour`
5. **No default parameters**: Use overloads
6. **Separation of concerns**: Data aggregates have no methods

## Roadmap

### Completed for Jenga Integration

- [x] DNS resolution via `uv_getaddrinfo`
- [x] Signal handling via async-signal-safe C bridge with `uv_async_t`

### Planned

- [ ] FS2 stream integration

## Licence

MIT © 2025 the original author(s)

## Acknowledgements

- [libuv](https://libuv.org/) - Cross-platform async I/O
- [cats-effect](https://typelevel.org/cats-effect/) - Purely functional effects
- [boilerplate](https://github.com/arashi01/boilerplate) - Effect utilities

