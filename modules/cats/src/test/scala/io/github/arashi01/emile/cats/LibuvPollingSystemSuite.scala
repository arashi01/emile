/*
 * Copyright 2025 the original author(s).
 * SPDX-License-Identifier: MIT
 */
package io.github.arashi01.emile.cats

import cats.effect.IO
import cats.effect.kernel.Resource
import munit.CatsEffectSuite

class LibuvPollingSystemSuite extends CatsEffectSuite:

  test("polling system configs are instance-local") {
    val cfgA = LoopConfig.empty.copy(metricsEnabled = Some(true))
    val cfgB = LoopConfig.empty

    val sysA = LibuvPollingSystem(cfgA)
    val sysB = LibuvPollingSystem(cfgB)

    assertEquals(sysA.loopConfig.metricsEnabled, Some(true))
    assertEquals(sysB.loopConfig.metricsEnabled, None)
  }

  test("poller lifecycle uses captured config per runtime") {
    val cfg = LoopConfig.empty.copy(metricsEnabled = Some(true))
    val sys = LibuvPollingSystem(cfg)

    val acquire = IO.blocking(sys.makePoller())
    val release = (p: LibuvPollingSystem.LibuvPoller) => IO.blocking(sys.closePoller(p))

    Resource.make(acquire)(release).use { poller =>
      IO {
        // Freshly created pollers start with no active handles; loop is idle but valid.
        assert(!poller.loop.isAlive, "new poller should start idle with no handles")
        assertEquals(sys.loopConfig.metricsEnabled, Some(true))
      }
    }
  }

