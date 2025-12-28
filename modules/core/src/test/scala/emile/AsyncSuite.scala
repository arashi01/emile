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
package emile

import munit.FunSuite

/**
 * Tests for Async handle operations.
 *
 * These tests link to and execute the real libuv library.
 */
class AsyncSuite extends FunSuite:
// scalafix:off

  test("Async.init creates a valid async handle"):
    var callbackInvoked = false

    val result = for
      loop <- Loop.create
      async <- Async.init(loop) { () =>
        callbackInvoked = true
      }
      _ <- async.send
      _ <- loop.run(RunMode.NoWait)
      _ = async.close
      _ <- loop.run(RunMode.Default)
      _ <- loop.close
    yield ()

    assert(result.isRight, s"Expected Right, got $result")
    assert(callbackInvoked, "Async callback should have been invoked")

  test("Async.send wakes up the event loop"):
    var wokenUp = false

    val result = for
      loop <- Loop.create
      async <- Async.init(loop) { () =>
        wokenUp = true
      }
      startNanos = System.nanoTime()
      // Send signal before running loop
      _ <- async.send
      // Run loop to process the signal
      _ <- loop.run(RunMode.NoWait)
      elapsedNanos = System.nanoTime() - startNanos
      _ = async.close
      _ <- loop.run(RunMode.Default)
      _ <- loop.close
    yield elapsedNanos

    assert(result.isRight, s"Expected Right, got $result")
    assert(wokenUp, "Async callback should have been invoked after send")
    result.foreach { elapsedNanos =>
      // Allow generous slack to avoid flakiness in CI
      assert(elapsedNanos < 200_000_000L, s"RunMode.NoWait should return promptly; took ${elapsedNanos / 1_000_000.0} ms")
    }

  test("Multiple sends may coalesce"):
    var invokeCount = 0

    val result = for
      loop <- Loop.create
      async <- Async.init(loop) { () =>
        invokeCount += 1
      }
      // Send multiple signals
      _ <- async.send
      _ <- async.send
      _ <- async.send
      // Run loop
      _ <- loop.run(RunMode.NoWait)
      _ = async.close
      _ <- loop.run(RunMode.Default)
      _ <- loop.close
    yield invokeCount

    assert(result.isRight, s"Expected Right, got $result")
    result.foreach { count =>
      // Multiple sends may coalesce into one callback
      assert(count >= 1, s"Callback should be invoked at least once, got $count")
      // Note: We can't assert count == 1 because coalescing is not guaranteed
    }

  test("Async Handle operations work"):
    val result = for
      loop <- Loop.create
      async <- Async.init(loop)(() => ())
      // Async handles are always active
      isActive = async.isActive
      hasRef1 = async.hasRef
      _ = async.unref
      hasRef2 = async.hasRef
      _ = async.ref
      hasRef3 = async.hasRef
      asyncLoop = async.loop
      handleType = async.handleType
      _ = async.close
      _ <- loop.run(RunMode.Default)
      _ <- loop.close
    yield (isActive, hasRef1, hasRef2, hasRef3, handleType)

    assert(result.isRight, s"Expected Right, got $result")
    result.foreach { case (isActive, hasRef1, hasRef2, hasRef3, handleType) =>
      assert(isActive, "Async handle should always be active")
      assert(hasRef1, "Async should be referenced by default")
      assert(!hasRef2, "Async should not be referenced after unref")
      assert(hasRef3, "Async should be referenced after ref")
      assertEquals(handleType, HandleType.Async)
    }

  test("Async with Timer integration"):
    var asyncFired = false
    var timerFired = false
    var asyncRef: Async[Open] = null.asInstanceOf[Async[Open]]
    var timerRef: Timer[Open] = null.asInstanceOf[Timer[Open]]

    val result = for
      loop <- Loop.create
      async <- Async.init(loop) { () =>
        asyncFired = true
      }
      timer <- Timer.init(loop)
      _ = { asyncRef = async; timerRef = timer }
      // Timer will send to async when it fires
      _ <- timer.start(Timeout.millis(10), Timeout.Zero) { () =>
        timerFired = true
        // Close timer after firing
        val _ = timerRef.close
        // Then close async
        val _ = asyncRef.close
      }
      // Also send async directly
      _ <- async.send
      _ <- loop.run(RunMode.Default)
      _ <- loop.close
    yield ()

    assert(result.isRight, s"Expected Right, got $result")
    assert(asyncFired, "Async callback should have fired")
    assert(timerFired, "Timer callback should have fired")

end AsyncSuite
