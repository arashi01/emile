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

import scala.annotation.targetName

/**
 * Monotonic timestamp in milliseconds from the event loop.
 *
 * Obtained from `Loop.now` which returns a cached timestamp.
 * Use `Loop.updateTime` to refresh the cached value.
 */
opaque type Timestamp = Long

object Timestamp:
  given CanEqual[Timestamp, Timestamp] = CanEqual.derived
  given Ordering[Timestamp] = Ordering.Long

  /** Construct from milliseconds since arbitrary epoch. */
  inline def apply(millis: Long): Timestamp = millis

  extension (t: Timestamp)
    /** Get the raw milliseconds value. */
    inline def millis: Long = t

    /** Calculate duration between two timestamps. */
    @targetName("minus")
    inline def -(other: Timestamp): Timeout = Timeout.millis(t - other)

    /** Add a duration to get a future timestamp. */
    @targetName("plus")
    inline def +(duration: Timeout): Timestamp = t + duration.toMillis

    /** Check if this timestamp is before another. */
    inline def isBefore(other: Timestamp): Boolean = t < other

    /** Check if this timestamp is after another. */
    inline def isAfter(other: Timestamp): Boolean = t > other
end Timestamp
