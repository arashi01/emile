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

import munit.FunSuite

/** Tests for PollEvent bitmask conversion. */
class PollEventSuite extends FunSuite:

  test("PollEvent.fromLibuv converts bitmask correctly"):
    assertEquals(PollEvent.fromLibuv(0), Set.empty[PollEvent])
    assertEquals(PollEvent.fromLibuv(1), Set(PollEvent.Readable))
    assertEquals(PollEvent.fromLibuv(2), Set(PollEvent.Writable))
    assertEquals(PollEvent.fromLibuv(3), Set(PollEvent.Readable, PollEvent.Writable))
    assertEquals(PollEvent.fromLibuv(4), Set(PollEvent.Disconnect))
    assertEquals(PollEvent.fromLibuv(8), Set(PollEvent.Prioritized))
    assertEquals(PollEvent.fromLibuv(15), Set(PollEvent.Readable, PollEvent.Writable, PollEvent.Disconnect, PollEvent.Prioritized))

  test("PollEvent.combine creates correct bitmask"):
    assertEquals(PollEvent.combine(), 0)
    assertEquals(PollEvent.combine(PollEvent.Readable), 1)
    assertEquals(PollEvent.combine(PollEvent.Writable), 2)
    assertEquals(PollEvent.combine(PollEvent.Readable, PollEvent.Writable), 3)
    assertEquals(PollEvent.combine(PollEvent.Readable, PollEvent.Writable, PollEvent.Disconnect), 7)

end PollEventSuite
