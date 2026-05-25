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

import cats.effect.IO
import cats.effect.unsafe.PollResult
import cats.effect.unsafe.PollingContext
import cats.effect.unsafe.PollingSystem
import cats.effect.unsafe.metrics.PollerMetrics

import emile.unsafe.LibuvPoller
import emile.unsafe.SignalSupervisor

/** The `cats.effect` [[cats.effect.unsafe.PollingSystem PollingSystem]] that plugs a libuv loop
  * into each work-stealing worker. Build it through [[LibuvPollingSystem$ LibuvPollingSystem]] and
  * install it with [[EmileIOApp]] or [[Emile.runtime]].
  */
final class LibuvPollingSystem private (config: LoopConfig) extends PollingSystem:

  type Api = LibuvPollingSystem.Access
  type Poller = LibuvPoller

  def makeApi(ctx: PollingContext[LibuvPoller]): LibuvPollingSystem.Access =
    new LibuvPollingSystem.Access(ctx)

  def makePoller(): LibuvPoller =
    val p = new LibuvPoller(config)
    SignalSupervisor.electSupervisor(p)
    p

  def closePoller(p: LibuvPoller): Unit = p.close()

  def poll(p: LibuvPoller, nanos: Long): PollResult = p.poll(nanos)

  // One uv_run iteration drains every ready event, so there is no separate ready-event step.
  def processReadyEvents(p: LibuvPoller): Boolean = false

  def needsPoll(p: LibuvPoller): Boolean = p.needsPoll

  def interrupt(t: Thread, p: LibuvPoller): Unit = p.interrupt()

  def close(): Unit = ()

  def metrics(p: LibuvPoller): PollerMetrics = LibuvPollingSystem.zeroMetrics

end LibuvPollingSystem

/** Factory for [[LibuvPollingSystem]], and the worker-facing [[LibuvPollingSystem.Access Access]]
  * handle onto a [[emile.unsafe.LibuvPoller LibuvPoller]].
  */
object LibuvPollingSystem:

  /** A libuv polling system with the default [[LoopConfig]]. */
  def apply(): LibuvPollingSystem = apply(LoopConfig.default)

  /** A libuv polling system tuned by `config`. */
  def apply(config: LoopConfig): LibuvPollingSystem = new LibuvPollingSystem(config)

  /** A single shared all-zero `PollerMetrics`. emile does not instrument poller metrics, and
    * cats-effect's own `PollerMetrics.noop` is `private[effect]` so cannot be reused.
    */
  private[emile] val zeroMetrics: PollerMetrics =
    new PollerMetrics:
      def operationsOutstandingCount(): Int = 0
      def totalOperationsSubmittedCount(): Long = 0L
      def totalOperationsSucceededCount(): Long = 0L
      def totalOperationsErroredCount(): Long = 0L
      def totalOperationsCanceledCount(): Long = 0L
      def acceptOperationsOutstandingCount(): Int = 0
      def totalAcceptOperationsSubmittedCount(): Long = 0L
      def totalAcceptOperationsSucceededCount(): Long = 0L
      def totalAcceptOperationsErroredCount(): Long = 0L
      def totalAcceptOperationsCanceledCount(): Long = 0L
      def connectOperationsOutstandingCount(): Int = 0
      def totalConnectOperationsSubmittedCount(): Long = 0L
      def totalConnectOperationsSucceededCount(): Long = 0L
      def totalConnectOperationsErroredCount(): Long = 0L
      def totalConnectOperationsCanceledCount(): Long = 0L
      def readOperationsOutstandingCount(): Int = 0
      def totalReadOperationsSubmittedCount(): Long = 0L
      def totalReadOperationsSucceededCount(): Long = 0L
      def totalReadOperationsErroredCount(): Long = 0L
      def totalReadOperationsCanceledCount(): Long = 0L
      def writeOperationsOutstandingCount(): Int = 0
      def totalWriteOperationsSubmittedCount(): Long = 0L
      def totalWriteOperationsSucceededCount(): Long = 0L
      def totalWriteOperationsErroredCount(): Long = 0L
      def totalWriteOperationsCanceledCount(): Long = 0L

  /** The worker-facing handle onto a [[emile.unsafe.LibuvPoller LibuvPoller]] - obtains the calling
    * worker's poller and tests poller ownership.
    */
  final class Access private[emile] (ctx: PollingContext[LibuvPoller]):

    private[emile] def withCurrentPoller[A](f: LibuvPoller => IO[A]): IO[A] =
      IO.async_[LibuvPoller](cb => ctx.accessPoller(p => cb(Right(p)))).flatMap(f)

    private[emile] def isOwnPoller(p: LibuvPoller): Boolean = ctx.ownPoller(p)

  /** The libuv poller of the calling work-stealing worker - the entry point through which a
    * libuv-backed operation reaches its loop. Fails with
    * `EmileError.Runtime.MissingLibuvPollingSystem` when the runtime carries no libuv polling
    * system.
    */
  private[emile] def currentPoller: IO[LibuvPoller] =
    IO.pollers.flatMap: pollers =>
      pollers.collectFirst { case access: Access => access } match
        case Some(access) => access.withCurrentPoller(IO.pure)
        case None => IO.raiseError(EmileError.Runtime.MissingLibuvPollingSystem)

end LibuvPollingSystem
