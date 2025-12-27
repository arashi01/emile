/*
 * Copyright 2025 the original author(s).
 * SPDX-License-Identifier: MIT
 */
package io.github.arashi01.emile.unsafe

import io.github.arashi01.emile.Async
import io.github.arashi01.emile.Loop
import io.github.arashi01.emile.Open
import io.github.arashi01.emile.RunMode
import munit.FunSuite

import java.util.concurrent.atomic.AtomicBoolean

class CallbackRegistrySuite extends FunSuite:
// scalafix:off

  override def beforeEach(context: BeforeEach): Unit =
    CallbackRegistry.clearAll()

  test("per-loop registration is isolated"):
    val loop1 = Loop.create.toOption.get
    val loop2 = Loop.create.toOption.get

    val id1 = CallbackRegistry.registerLoop(loop1.ptrUnsafe, "loop1")
    val id2 = CallbackRegistry.registerLoop(loop2.ptrUnsafe, "loop2")

    assertEquals(CallbackRegistry.find(loop1.ptrUnsafe, id1), Some("loop1"))
    assertEquals(CallbackRegistry.find(loop2.ptrUnsafe, id2), Some("loop2"))
    assert(CallbackRegistry.find(loop1.ptrUnsafe, id2).isEmpty)
    assert(CallbackRegistry.find(loop2.ptrUnsafe, id1).isEmpty)

    assert(CallbackRegistry.unregister(loop1.ptrUnsafe, id1))
    assert(CallbackRegistry.unregister(loop2.ptrUnsafe, id2))
    assertEquals(CallbackRegistry.size(loop1.ptrUnsafe), 0)
    assertEquals(CallbackRegistry.size(loop2.ptrUnsafe), 0)

    val _ = loop1.close
    val _ = loop2.close

  test("request contexts carry loop pointer and callback id"):
    val loop = Loop.create.toOption.get
    val id = CallbackRegistry.registerLoop(loop.ptrUnsafe, "ctx-callback")

    val ctx = CallbackRegistry.encodeRequest(loop.ptrUnsafe, id)
    val loopPtr = CallbackRegistry.requestLoopPtr(ctx)
    val cbId = CallbackRegistry.requestCallbackId(ctx)

    assertEquals(loopPtr, loop.ptrUnsafe)
    assertEquals(cbId, id)
    assertEquals(CallbackRegistry.find(loopPtr, cbId), Some("ctx-callback"))

    CallbackRegistry.freeRequest(ctx)
    CallbackRegistry.unregister(loop.ptrUnsafe, id): Unit
    val _ = loop.close

  test("async callbacks stay on their owning loop across threads"):
    val loop1 = Loop.create.toOption.get
    val loop2 = Loop.create.toOption.get

    val fired1 = AtomicBoolean(false)
    val fired2 = AtomicBoolean(false)

    var async1: Async[Open] = null.asInstanceOf[Async[Open]]
    var async2: Async[Open] = null.asInstanceOf[Async[Open]]

    val init1 = Async.init(loop1) { () =>
      fired1.set(true)
      val _ = async1.close
      loop1.stop
    }
    val init2 = Async.init(loop2) { () =>
      fired2.set(true)
      val _ = async2.close
      loop2.stop
    }

    assert(init1.isRight && init2.isRight)
    async1 = init1.toOption.get
    async2 = init2.toOption.get

    val t1 = Thread(() => { val _ = loop1.run(RunMode.Default); val _ = loop1.close })
    val t2 = Thread(() => { val _ = loop2.run(RunMode.Default); val _ = loop2.close })
    t1.start()
    t2.start()

    // Give loops a moment to enter run
    Thread.sleep(10)

    val _ = async1.send
    val _ = async2.send

    t1.join(2000)
    t2.join(2000)

    assert(fired1.get(), "async callback on loop1 was not invoked")
    assert(fired2.get(), "async callback on loop2 was not invoked")
    assertEquals(CallbackRegistry.size(loop1.ptrUnsafe), 0)
    assertEquals(CallbackRegistry.size(loop2.ptrUnsafe), 0)

end CallbackRegistrySuite
