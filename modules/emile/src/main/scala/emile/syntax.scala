/*
 * Copyright 2025, 2026 Ali Rashid
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

import boilerplate.effect.EffIO
import cats.effect.kernel.Resource
import fs2.Pipe
import fs2.Stream

/** Typed-error effect for the libuv runtime - a `cats.effect.IO` carrying an `Either[E, A]`,
  * covariant in both the error `E` and the value `A`, so a narrower error widens implicitly at
  * every call site.
  */
type EmIO[+E, +A] = EffIO[E, A]

/** An fs2 `Stream` scoped over the [[EmIO]] effect; covariant in the error `E`. */
type EmStream[+E, +A] = Stream[EffIO.Of[E], A]

/** A cats-effect `Resource` scoped over the [[EmIO]] effect. Invariant in `E` - `Resource` is
  * invariant in its effect type - so the error channel is widened through the `widen` extension
  * rather than implicitly.
  */
type EmResource[E, A] = Resource[EffIO.Of[E], A]

/** An fs2 `Pipe` scoped over the [[EmIO]] effect. Invariant in `E` and so not error-widenable; it
  * is applied with `through` to a stream of its own error type.
  */
type EmPipe[E, -I, +O] = Pipe[EffIO.Of[E], I, O]

/** Widens an [[EmResource]]'s error channel to a supertype - the explicit counterpart to the
  * implicit widening that the covariant [[EmIO]] and [[EmStream]] get for free.
  */
extension [E, A](resource: EmResource[E, A]) def widen[E2 >: E]: EmResource[E2, A] = resource.mapK(EffIO.widenK[E, E2])
