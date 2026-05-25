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

import scala.concurrent.duration.*

import boilerplate.effect.EffIO
import cats.effect.IO
import fs2.Chunk
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.Ipv4Address
import com.comcast.ip4s.Port
import com.comcast.ip4s.SocketAddress

/** Covers [[TcpSocket.readPtr]] / [[TcpSocket.writePtr]] - the zero-copy one-shot read/write pair. */
final class TcpZeroCopySpec extends EmileSuite:

  private val anyLoopback: SocketAddress[IpAddress] =
    SocketAddress(Ipv4Address.fromString("127.0.0.1").get, Port.fromInt(0).get)

  test("readPtr feeding writePtr round-trips the payload") {
    val payload: Chunk[Byte] = Chunk.array("zero-copy emile".getBytes("UTF-8"))
    Tcp
      .bind(anyLoopback)
      .widen[EmileError]
      .use(server => EffIO.liftF(zeroCopyEcho(server, payload)))
      .absolve
      .timeout(5.seconds)
  }

  private def zeroCopyEcho(server: TcpServer, payload: Chunk[Byte]): IO[Unit] =
    val srvWork: IO[Unit] =
      server.connections
        .evalMap(socket =>
          socket
            .readPtr((ptr, len) => socket.writePtr(ptr, len))
            .map(_ => ())
        )
        .head
        .compile
        .drain
        .absolve

    val cliWork: IO[Unit] =
      Tcp
        .connect(server.address.asIpUnsafe)
        .widen[EmileError]
        .use(socket =>
          EffIO.liftF(
            for
              _ <- socket.write(payload).absolve
              echo <- socket.read(payload.size).absolve
              _ <- IO(assertEquals(echo.fold(List.empty[Byte])(_.toList), payload.toList))
            yield ()
          )
        )
        .absolve

    srvWork.background.use(_ => cliWork)
  end zeroCopyEcho

end TcpZeroCopySpec
