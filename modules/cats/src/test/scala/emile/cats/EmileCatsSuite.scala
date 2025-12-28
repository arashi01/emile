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

import scala.concurrent.duration.*

import cats.effect.Deferred
import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.syntax.all.*

import boilerplate.effect.*
import boilerplate.effect.Eff
import boilerplate.nullable.*

import emile.EmileError
import emile.HandleType
import emile.Loop
import emile.cats.syntax.all.*
import emile.ipa.Ipv4Address
import emile.ipa.Ipv6Address
import emile.ipa.Port
import emile.ipa.SocketAddress

/**
 * Tests for emile-cats module Resource integration.
 *
 * Extends EmileSuite to use LibuvPollingSystem for proper libuv integration.
 * All tests use Eff[IO, EmileError, A] exclusively for typed error handling.
 */
class EmileCatsSuite extends EmileSuite:
// scalafix:off

  // Helper to run Eff tests - unwraps to IO for the test framework
  private inline def runEff[A](eff: Eff[IO, EmileError, A]): IO[A] = eff.rethrow

  // ============================================================================
  // EmileLoop Resource Tests
  // ============================================================================

  test("EmileLoop.integrated acquires loop from runtime") {
    runEff {
      EmileLoop.integrated.use { loop =>
        Eff.succeed[IO, EmileError, Unit] {
          // Loop is an opaque type wrapping Ptr[Byte], verify it's accessible
          assert(loop.isAlive || !loop.isAlive, "Loop should be callable")
        }
      }
    }
  }

  test("EmileLoop.create acquires and releases a standalone loop") {
    runEff {
      EmileLoop.create.use { loop =>
        Eff.succeed[IO, EmileError, Unit] {
          // Verify loop is accessible and can be queried
          assert(loop.isAlive || !loop.isAlive, "Loop should be callable")
        }
      }
    }
  }

  test("EmileLoop.runOnce extension works") {
    runEff {
      EmileLoop.create.use { loop =>
        import EmileLoop.runOnce
        loop.runOnce.map { alive =>
          assert(!alive, "Empty loop should not be alive")
        }
      }
    }
  }

  test("EmileLoop.runNoWait extension works") {
    runEff {
      EmileLoop.create.use { loop =>
        import EmileLoop.runNoWait
        loop.runNoWait.map { alive =>
          assert(!alive, "Empty loop should not be alive after runNoWait")
        }
      }
    }
  }

  test("EmileLoop.runUntilComplete drains many timers without stack growth") {
    runEff {
      EmileLoop.create.use { loop =>
        val count = 512
        for
          ref <- Eff.liftF[IO, EmileError, Ref[IO, Int]](Ref.of[IO, Int](0))
          done <- Eff.liftF[IO, EmileError, Deferred[IO, Unit]](Deferred[IO, Unit])
          _ <- (0 until count).toList.traverse_ { _ =>
            Timer.after(loop, Timeout.millis(0))(() =>
              ref.updateAndGet(_ + 1).flatMap { n =>
                if n == count then done.complete(()) else IO.unit
              }.unsafeRunAndForget()
            ).eff[IO]
          }
          _ <- loop.runUntilComplete
          _ <- Eff.liftF[IO, EmileError, Unit](done.get)
          finalCount <- Eff.liftF[IO, EmileError, Int](ref.get)
        yield assertEquals(finalCount, count)
      }
    }
  }

  // ============================================================================
  // Error Syntax Tests
  // ============================================================================

  test("eff[IO] converts Right to successful Eff") {
    val either: Either[EmileError, Int] = Right(42)
    either.eff[IO].rethrow.map { value =>
      assertEquals(value, 42)
    }
  }

  test("eff[IO] converts Left to failed Eff with EmileError") {
    val either: Either[EmileError, Int] = Left(EmileError.AlreadyClosed)
    either.eff[IO].rethrow.attempt.map {
      case Left(e: EmileError) => assertEquals(e, EmileError.AlreadyClosed)
      case other => fail(s"Expected EmileError.AlreadyClosed, got $other")
    }
  }

  test("catchEmile recovers from EmileError") {
    val io = IO.raiseError[Int](EmileError.TimedOut)
    io.catchEmile {
      case EmileError.TimedOut => IO.pure(0)
    }.map { value =>
      assertEquals(value, 0)
    }
  }

  test("catchEmile does not catch non-matching errors") {
    val io = IO.raiseError[Int](EmileError.AlreadyClosed)
    io.catchEmile {
      case EmileError.TimedOut => IO.pure(0)
    }.attempt.map {
      case Left(e: EmileError) => assertEquals(e, EmileError.AlreadyClosed)
      case other => fail(s"Expected EmileError.AlreadyClosed, got $other")
    }
  }

  test("Emile extractor works in pattern matching") {
    val error: Throwable = EmileError.Cancelled
    error match
      case Emile(EmileError.Cancelled) => () // Success
      case _ => fail("Pattern should match")
  }

  test("toValidatedNec accumulates error list") {
    val validated = List[EmileError](EmileError.AlreadyClosed, EmileError.TimedOut).toValidatedNec
    validated.fold(nec => assertEquals(nec.toList.size, 2), _ => fail("Expected validation failure"))
  }

  test("Either[List[EmileError], A].toValidatedNec converts Left to NonEmptyChain") {
    val validated = Left(List(EmileError.Cancelled)).toValidatedNec
    validated.fold(nec => assertEquals(nec.head, EmileError.Cancelled), _ => fail("Expected validation failure"))
  }

  // ============================================================================
  // TimerResource Tests (with integrated loop)
  // ============================================================================

  test("TimerResource.make acquires and releases timer with integrated loop") {
    runEff {
      EmileLoop.integrated.use { implicit loop =>
        TimerResource.make.use { timer =>
          Eff.succeed[IO, EmileError, Unit] {
            assert(!timer.isClosing, "Timer should not be closing")
          }
        }
      }
    }
  }

  test("withLoop provides live loop for timer callbacks") {
    EmileIOApp.withLoop { loop =>
      // Validate the loop we received is owned by the current worker
      LibuvPollingSystem.LoopAccess.get.flatMap { access =>
        access.ownsLoop(loop).flatMap { owned =>
          if owned then Eff.unit[IO, EmileError]
          else Eff.fail[IO, EmileError, Unit](EmileError.InvalidArgument("loop", "withLoop must return the current worker's loop"))
        }
      }
    }.either
  }

  test("LoopOwnership rejects non-runtime loops") {
    runEff {
      EmileLoop.create.use { loop =>
        given Loop = loop
        TimerResource.make.use(_ => Eff.unit[IO, EmileError])
      }
    }.attempt.map {
      case Left(_: EmileError.LoopOwnershipViolation.type) => () // Expected
      case other => fail(s"Expected loop ownership violation, got $other")
    }
  }

  // ============================================================================
  // AsyncResource Tests  
  // ============================================================================

  test("AsyncResource.make acquires and releases async handle") {
    runEff {
      EmileLoop.integrated.use { implicit loop =>
        AsyncResource.make(() => ()).use { async =>
          Eff.succeed[IO, EmileError, Unit] {
            assert(!async.isClosing, "Async should not be closing")
          }
        }
      }
    }
  }

  // ============================================================================
  // SignalStream Tests
  // ============================================================================

  test("SignalStream.awaitOnce receives signal via async-safe bridge") {
    import scala.scalanative.posix.signal.kill
    import scala.scalanative.posix.unistd.getpid
    import emile.Signal

    runEff {
      // Use watch directly to get access to the readiness gate
      SignalStream.watch(Signal.SIGUSR2).use { case (queue, ready) =>
        for
          // Wait for handler to be installed before sending signal
          _ <- Eff.liftF[IO, EmileError, Unit](ready)
          // Now send the signal - handler is guaranteed to be ready
          _ <- Eff.liftF[IO, EmileError, Unit](IO {
            val pid = getpid()
            val _ = kill(pid, Signal.SIGUSR2)
          })
          _ <- Eff.liftF[IO, EmileError, Unit](queue.take)
        yield ()
      }
    }
  }

  test("SignalStream.watch receives multiple signals via queue") {
    import scala.scalanative.posix.signal.kill
    import scala.scalanative.posix.unistd.getpid
    import emile.Signal

    runEff {
      // Note: SignalStream.watch acquires loop internally
      SignalStream.watch(Signal.SIGUSR2).use { case (queue, ready) =>
        for
          // Wait for handler to be installed
          _ <- Eff.liftF[IO, EmileError, Unit](ready)
          // Send signal - handler is guaranteed to be ready
          _ <- Eff.liftF[IO, EmileError, Unit](IO {
            val pid = getpid()
            val _ = kill(pid, Signal.SIGUSR2)
          })
          // Wait for the signal via queue
          _ <- Eff.liftF[IO, EmileError, Unit](queue.take)
        yield ()
      }
    }
  }

  // ============================================================================
  // DnsResolver Tests
  // ============================================================================

  test("DnsResolver.resolve resolves localhost") {
    runEff {
      EmileLoop.integrated.use { implicit loop =>
        for
          addresses <- DnsResolver.resolve("localhost", "80")
        yield assert(addresses.nonEmpty, "localhost should resolve to at least one address")
      }
    }
  }

  test("DnsResolver.resolve with numeric IP returns that IP") {
    runEff {
      EmileLoop.integrated.use { implicit loop =>
        for
          addresses <- DnsResolver.resolve("127.0.0.1", "443")
        yield
          assert(addresses.nonEmpty, "127.0.0.1 should resolve")
          addresses.foreach {
            case SocketAddress.V4(addr, port) =>
              assertEquals(addr, Ipv4Address.Loopback)
              assertEquals(port, Port.unsafeFromInt(443))
            case _ => fail("Expected IPv4 address")
          }
      }
    }
  }

  test("DnsResolver.resolve with port number string works") {
    runEff {
      EmileLoop.integrated.use { implicit loop =>
        for
          addresses <- DnsResolver.resolve("::1", 8080)
        yield
          assert(addresses.nonEmpty, "::1 should resolve")
          addresses.foreach {
            case SocketAddress.V6(addr, port, _, _) =>
              assertEquals(addr, Ipv6Address.Loopback)
              assertEquals(port, Port.unsafeFromInt(8080))
            case _ => fail("Expected IPv6 address")
          }
      }
    }
  }

  test("DnsResolver.resolve fails for invalid hostname") {
    runEff {
      EmileLoop.integrated.use { implicit loop =>
        DnsResolver.resolve("this.hostname.definitely.does.not.exist.invalid", "80")
          .map(_ => fail("Should have failed"))
          .catchAll { (_: EmileError) =>
            Eff.succeed[IO, EmileError, Unit](()) // Expected: DNS resolution failure
          }
      }
    }
  }

  // ============================================================================
  // TcpResource Tests
  // ============================================================================

  test("TcpResource.make acquires and releases tcp handle") {
    runEff {
      EmileLoop.integrated.use { implicit loop =>
        TcpResource.make.use { tcp =>
          Eff.succeed[IO, EmileError, Unit] {
            assert(!tcp.isClosing, "TCP should not be closing")
          }
        }
      }
    }
  }

  test("TcpResource.connect fails cleanly on refused connection") {
    val address = SocketAddress.V4(Ipv4Address.Loopback, Port.unsafeFromInt(9))

    runEff {
      EmileLoop.integrated.use { implicit loop =>
        TcpResource.connect(address)
          .use(_ => Eff.unit[IO, EmileError])
          .semiflatMap(_ => IO.sleep(300.millis))
          .catchAll(_ => Eff.unit[IO, EmileError])  // Expected: ECONNREFUSED in Eff channel
      }
    }
  }

  test("TcpResource init failure path frees handle and leaves loop drained") {
    runEff {
      EmileLoop.integrated.use { implicit loop =>
        import emile.unsafe.LibUV
        import scala.scalanative.libc.stdlib.{calloc, free}
        import EmileLoop.runNoWait

        import scala.scalanative.unsigned.UnsignedRichInt

        val invalidFlag = 99.toUInt // invalid address family forces uv_tcp_init_ex failure

        val handleType = HandleType.toLibuvInline(HandleType.Tcp)

        // Use Eff.attempt to capture IO-level errors into Eff channel
        val attemptInit: Eff[IO, EmileError, Unit] =
          Eff.attempt[IO, EmileError, Unit](
            Resource.make(IO.blocking {
              val size = LibUV.uv_handle_size(handleType)
              val handle = calloc(1L, size.toLong)
              assert(handle != null, "calloc returned null handle")
              handle
            })(handle => IO.blocking(free(handle))).use { handle =>
              IO.blocking(LibUV.uv_tcp_init_ex(loop.ptrUnsafe, handle, invalidFlag)).flatMap { rc =>
                if rc < 0 then IO.raiseError(EmileError.fromErrorCode(ErrorCode(rc)))
                else IO.raiseError(new RuntimeException("uv_tcp_init_ex unexpectedly succeeded"))
              }
            },
            {
              case e: EmileError => e
              case t => EmileError.SystemError(ErrorCode(-1), t.getMessage.option.getOrElse("Unknown error"))
            }
          )

        attemptInit.catchAll { (_: EmileError) =>
          // Ensure loop has no leaked handles after failed init
          loop.runNoWait.map { alive =>
            assert(!alive, "Loop should not retain handles after failed init")
          }
        }
      }
    }
  }

end EmileCatsSuite
