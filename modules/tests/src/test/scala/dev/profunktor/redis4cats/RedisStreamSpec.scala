/*
 * Copyright 2018-2021 ProfunKtor
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

package dev.profunktor.redis4cats

import cats.effect.IO
import cats.implicits.toTraverseOps
import dev.profunktor.redis4cats.streams.data.XAddMessage

import scala.concurrent.duration.DurationInt

class RedisStreamSpec extends Redis4CatsFunSuite(false) {

  test("append/read to/from a stream") {
    readWriteTest("test-stream", 1).unsafeToFuture()
  }

  test("append/read to/from a stream - flakiness test") {
    (1 to 10).toList
      .traverse(i => readWriteTest(s"test-stream-$i", 100))
      .void
      .unsafeToFuture()
  }

  test("reading from a silent stream should not fail with RedisCommandTimeoutException") {
    timeoutingOperationTest { (options, restartOnTimeout) =>
      fs2.Stream.resource(withRedisStreamOptionsResource(options)).flatMap { case (readStream, _) =>
        // This stream has no data and previously reading from such stream would fail with an exception
        readStream.read(Set("test-stream-expiration"), 1, restartOnTimeout = restartOnTimeout)
      }
    }
  }

  private def readWriteTest(streamKey: String, length: Long): IO[Unit] =
    IO.fromFuture {
      IO {
        withRedisStream { (readStream, writeStream) =>
          val read = readStream.read(Set(streamKey), 1)
          val write =
            writeStream.append(fs2.Stream(XAddMessage(streamKey, Map("hello" -> "world"))).repeatN(length))

          read
            .concurrently(write)
            .take(length)
            .interruptAfter(3.seconds)
            .compile
            .lastOrError
            .map { read =>
              assertEquals(read.key, streamKey)
              assertEquals(read.body, Map("hello" -> "world"))
            }
        }
      }
    }
}
