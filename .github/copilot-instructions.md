# Émile: AI Coding Agent Instructions

## Project Overview

**Émile** is a Scala 3 Native async I/O library backed by libuv with first-class cats-effect integration. The codebase prioritises zero-overhead FFI, phantom-type safety, and explicit error handling via typed error channels.

## Design Philosophy

- **Clean-sheet implementation** with zero technical debt; no backwards compatibility.
- Proposed changes **replace** existing APIs outright; remove legacy paths, do not deprecate.
- Primary objective: elegant, pleasant, type-safe Scala 3 functional API.
- Minimal runtime overhead using current Scala 3 features across the stack.
- **Jenga integration**: Émile serves as the I/O foundation for the Jenga HTTP/2 server framework.

## Architecture

```
┌──────────────┐    ┌───────────────┐    ┌───────────────┐
│  emile-ipa   │───►│  emile-core   │───►│  emile-cats   │
│ (addresses)  │    │ (libuv FFI)   │    │ (cats-effect) │
└──────────────┘    └───────────────┘    └───────────────┘
     Cross-platform       Native only          Native only
```

### Module Responsibilities

| Module | Platform | Description |
|--------|----------|-------------|
| `emile-ipa` | JVM/JS/Native | Zero-allocation IP/socket address types with compile-time literal validation |
| `emile-core` | Native | Direct libuv bindings with phantom-state handles (`Loop`, `Tcp`, `Timer`, `Async`, `Poll`) |
| `emile-cats` | Native | cats-effect runtime integration via `LibuvPollingSystem`; `Eff` typed error channels |

## Error Handling (Fundamental Invariant)

### Core API Invariants

1. **Never surface raw `IO`** in public API. All public methods return `Eff[IO, E, A]`.
2. **Typed error channels only**:
   - `AddressError` for emile-ipa
   - `EmileError` for emile-core and emile-cats
   - `JengaError` for all jenga modules
3. **Only `*Unsafe` suffixed methods may throw**. All other methods return errors as values.
4. **Error ADTs must be comprehensive** for useful end-user debugging.
5. **Upstream errors wrapped** in our ADT types.

### Error Type Separation

| Module | Error ADT | Usage |
|--------|-----------|-------|
| `emile-ipa` | `AddressError` | Address parsing/validation failures |
| `emile-core` | `EmileError` | libuv operations, allocation, I/O |
| `emile-cats` | `EmileError` | Lifted via `Eff[IO, EmileError, A]` |
| `jenga-*` | `JengaError` | HTTP/2 protocol, transport, handler errors |

### Error Handling Rules

1. **Never throw**. Return `Either[EmileError, A]` (core) or `Either[AddressError, A]` (ipa).
2. **Upstream error wrapping**: All libuv/FFI errors wrapped in our ADT types.
3. **No escaping exceptions**: Only `*Unsafe` suffixed methods may throw.
4. **Typed error channels**: Use `boilerplate.effect.Eff` for typed errors in effect layer.
5. Extend error ADTs where appropriate for useful end-user error handling.

### The Eff Pattern

```scala
import boilerplate.effect.*

// Eff[F, E, A] is opaque type for F[Either[E, A]]
// Use Eff.Of[IO, EmileError] as higher-kinded type alias

// Resource with typed error channel
def make(using Loop): Resource[Eff.Of[IO, EmileError], Tcp[Open]]

// Converting Either to Eff
val tcp: Eff[IO, EmileError, Tcp[Open]] = Tcp.init(loop).eff[IO]

// Unwrapping Eff to F[Either[E, A]]
val io: IO[Either[EmileError, Tcp[Open]]] = tcp.either
```

## Handle Lifecycle (Phantom Types)

Handles use opaque types with phantom state evidence:

```scala
opaque type Tcp[S <: HandleState] = Ptr[Byte]
type OpenTcp = Tcp[Open]
type ClosedTcp = Tcp[Closed]
```

- `Handle[Open]`: Active handle ready for operations
- `Handle[Closed]`: Closed handle; operations prevented at compile time
- **Never expose `Ptr` directly** in public APIs

## Per-Loop Callback Registry

All callbacks are stored per-loop via `uv_loop_set_data`/`uv_loop_get_data`:
- No global mutable state
- Thread-safe multi-loop architectures supported
- Callbacks referenced by `Long` ID, not raw object pointers (GC-safe)

## Scala 3 Idioms (Core Requirements)

### Separation of Data from Behaviour

```scala
// Data aggregates contain ZERO methods
enum SocketAddress:
  case V4(address: Ipv4Address, port: Port)
  case V6(address: Ipv6Address, port: Port, flowInfo: FlowInfo, scopeId: ScopeId)

// All behaviour in companion objects
object SocketAddress:
  given CanEqual[SocketAddress, SocketAddress] = ...
  def from(value: String): Either[AddressError, SocketAddress] = ...
  extension (addr: SocketAddress)
    def show: String = ...
```

### No Default Parameters

```scala
// WRONG
def from(value: String, strict: Boolean = true)

// CORRECT - use overloads
def from(value: String): Either[AddressError, A]
def from(value: String, strict: Boolean): Either[AddressError, A]
```

### Naming Conventions

- **British English**: `initialise`, `colour`, `behaviour`
- **Single-word identifiers** preferred: `parse` over `parseInput`
- **Overload** methods with same semantics: `from(s: String)`, `from(i: Int)`

### Opaque Types

```scala
opaque type Port = Int
object Port:
  inline def apply(inline value: Int): Port = ...  // compile-time validated
  def from(value: Int): Either[AddressError, Port] = ...  // runtime validated
  extension (p: Port)
    transparent inline def value: Int = p
```

### Extension Methods in Companions

```scala
object Tcp:
  given [S <: HandleState]: Handle[Tcp[S]] = Handle.fromPtr(_.ptr)
  
  extension (tcp: Tcp[Open])
    def bind(address: SocketAddress): Either[EmileError, Unit] = ...
```

## cats-effect Integration

### LibuvPollingSystem

libuv becomes THE runtime poller for cats-effect:

```scala
object MyApp extends EmileIOApp:
  override def loopConfig: LoopConfig = LoopConfig.withMetrics
  
  def run(args: List[String]): IO[ExitCode] =
    EmileIOApp.withLoop { loop =>
      // loop is the worker thread's libuv loop
      TcpResource.bind(address)(using loop).use { tcp =>
        // ...
      }.either
    }.fold(
      err => IO.println(s"Error: $err").as(ExitCode.Error),
      _ => IO.pure(ExitCode.Success)
    )
```

### Resource Pattern

All handle resources use typed error channels:

```scala
object TcpResource:
  def make(using Loop): Resource[Eff.Of[IO, EmileError], Tcp[Open]]
  def bind(address: SocketAddress)(using Loop): Resource[Eff.Of[IO, EmileError], Tcp[Open]]
  def connect(address: SocketAddress)(using Loop): Resource[Eff.Of[IO, EmileError], Tcp[Open]]
```

## Build & Test

```bash
# Full build
sbt compile

# Run tests (requires libuv)
sbt test

# Code style check
sbt staticCheck

# Auto-format
sbt format
```

### Compiler Settings

- **Production**: `-Yexplicit-nulls -Xfatal-warnings`
- **Tests**: No `-Xfatal-warnings` to reduce noise
- **Key flags**: `-language:strictEquality`, `-Xkind-projector`, `-Wunused:all`

## Scalafix Suppression

### Format Rules

```scala
// For individual statements - NO DisableSyntax directive
throw e // scalafix:ok; cats-effect API requires throwing

// For code blocks/files - MUST list specific rules
// scalafix:off DisableSyntax.null, DisableSyntax.var; libuv FFI
// ... code ...
// scalafix:on
```

## Common Implementation Patterns

### Adding a Handle Type

1. Define opaque type: `opaque type MyHandle[S <: HandleState] = Ptr[Byte]`
2. Provide factory: `def init(loop: Loop): Either[EmileError, MyHandle[Open]]`
3. Implement `given Handle[MyHandle[_]]` in companion
4. Add FFI bindings in `unsafe/LibUV.scala`
5. Create `MyHandleResource` in `emile-cats`
6. Write tests with real libuv

### Configuration Pattern

```scala
final case class MyConfig private (
    option1: Option[Int],
    option2: Option[Boolean]
):
  def withOption1(v: Int): MyConfig = copy(option1 = Some(v))
  def hasOverrides: Boolean = option1.isDefined || option2.isDefined

object MyConfig:
  val empty: MyConfig = MyConfig(None, None)
```

## Upstream References

All upstream APIs must be traced through source code in `external-references/`:

| Reference | Purpose |
|-----------|---------|
| `external-references/libuv` | C API semantics, handle lifecycle, error codes |
| `external-references/cats-effect` | IO runtime, Resource, PollingSystem |
| `external-references/cats` | Type classes, core abstractions |
| `external-references/boilerplate` | Eff effect type, nullable extensions |
| `external-references/scala-native` | FFI patterns, memory management |
| `external-references/scala-3-documentation` | Language features, metaprogramming |
| `external-references/jenga-framework` | HTTP/2 framework (migration target) |

## Future: Jenga Integration

Émile will serve as the I/O foundation for Jenga HTTP/2 server:

```
┌─────────────┐    ┌───────────────┐    ┌───────────────┐
│ jenga-api   │───►│ jenga-nghttp2 │───►│ jenga-engine  │───► jenga-server
│ (HTTP types)│    │ (FFI bindings)│    │ (FS2 bridge)  │
└─────────────┘    └───────────────┘    └───────────────┘
                          │                    │
                          ▼                    ▼
                    emile-core            emile-cats
```

### Planned Additions for Jenga

- **DNS Resolution**: `Dns.resolve` via `uv_getaddrinfo`
- **Signal Handling**: `Signal` handle for graceful shutdown
- **FS2 Integration**: Stream bridges for HTTP/2 frame processing

## Pitfalls to Avoid

- **Leaking callbacks**: Always deregister before handle close
- **Default loop mutations**: Never call `Loop.close` on default loop
- **Blocking the loop**: No blocking calls inside callbacks
- **Null pointer dereference**: Use `-Yexplicit-nulls`; unchecked casts need `@unchecked`
- **Hardcoded buffer sizes**: Use `sizeof[T]` or `stackalloc`
- **Default parameters**: Prohibited; use overloads
- **Methods on data**: Prohibited; use companion extension methods
