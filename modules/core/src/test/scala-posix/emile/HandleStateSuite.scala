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

import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*

import munit.FunSuite

/** POSIX-specific HandleState tests for Poll handles.
  *
  * Poll tests require POSIX pipes for file descriptor creation.
  */
class PosixHandleStateSuite extends FunSuite:

  private val parent = new HandleStateSuite

  test("Poll init returns Poll[Open]"):
    withPipe { (readFd, _) =>
      parent.withLoop { loop =>
        val result = Poll.init(loop, readFd)
        assert(result.isRight)
        // The fact this compiles proves init returns Poll[Open]
        val poll: Poll[Open] = result.toOption.get
        assert(poll.closeSync.isRight)
      }
    }

  test("Poll closeSync completes without leaks"):
    withPipe { (readFd, _) =>
      parent.withLoop { loop =>
        Poll.init(loop, readFd).foreach { poll =>
          val closeResult = poll.closeSync
          assert(closeResult.isRight)
        }
      }
    }

  test("Poll can start/stop only in Open state"):
    withPipe { (readFd, _) =>
      parent.withLoop { loop =>
        Poll.init(loop, readFd).foreach { poll =>
          // These compile because poll is Poll[Open]
          val startResult = poll.start(PollEvent.Readable)((_, _) => ())
          assert(startResult.isRight)
          val stopResult = poll.stop
          assert(stopResult.isRight)
          assert(poll.closeSync.isRight)
        }
      }
    }

  private def withPipe(f: (Int, Int) => Unit): Unit =
    val pipefd = stackalloc[Int](2)
    val result = unistd.pipe(pipefd)
    assert(result == 0, s"pipe() failed with $result")

    val readFd = pipefd(0)
    val writeFd = pipefd(1)

    f(readFd, writeFd)
    val _ = unistd.close(readFd)
    val _ = unistd.close(writeFd)
  end withPipe

end PosixHandleStateSuite
