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

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import scala.concurrent.duration.*

import boilerplate.effect.EffIO
import cats.effect.IO
import cats.effect.Resource

/** Covers [[FS]]: a real change to a watched path surfaces on the event stream, and a watch of a
  * missing path fails with a typed error.
  */
final class FSWatchSpec extends EmileSuite:

  test("watch reports a change to the watched file") {
    tempFile("emile FS watch probe".getBytes("UTF-8")).use: file =>
      FS.watch(file.toPath)
        .use(fs =>
          EffIO.liftF(
            for
              _ <- IO.blocking(append(file, " changed".getBytes("UTF-8")))
              events <- fs.events.take(1).compile.toList.absolve.timeout(10.seconds)
              _ <- IO(assert(events.exists(_.changes.nonEmpty), s"expected a filesystem change, got: $events"))
            yield ()
          )
        )
        .absolve
  }

  test("watch of a missing path fails with a typed IO error") {
    val missing = Path.of(s"/tmp/emile-fswatch-missing-${System.nanoTime}")
    FS.watch(missing).use(_ => EffIO.liftF(IO.unit)).either.map {
      case Left(_: EmileError.IO) => ()
      case other => fail(s"expected EmileError.IO, got: $other")
    }
  }

  private def tempFile(content: Array[Byte]): Resource[IO, File] =
    Resource.make(IO(writeTempFile(content)))(file => IO(file.delete(): Unit))

  private def writeTempFile(content: Array[Byte]): File =
    val file = File.createTempFile("emile-fswatch", ".tmp")
    append(file, content)
    file

  private def append(file: File, content: Array[Byte]): Unit =
    val out = new FileOutputStream(file, true)
    try out.write(content)
    finally out.close()

end FSWatchSpec
