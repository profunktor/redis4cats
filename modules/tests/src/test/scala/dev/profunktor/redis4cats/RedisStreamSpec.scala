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
import cats.implicits.catsSyntaxParallelSequence1
import dev.profunktor.redis4cats.streams.Streaming
import dev.profunktor.redis4cats.streams.data.XAddMessage

import scala.concurrent.duration.DurationInt
import scala.util.Random

class RedisStreamSpec extends Redis4CatsFunSuite(false) {

  // FIXME: Flaky test -> https://github.com/profunktor/redis4cats/issues/460
  test("append/read to/from a stream".ignore) {
    withRedisStream[Unit] { stream =>
      val read  = stream.read(Set("test-stream"), 1)
      val write = stream.append(fs2.Stream(XAddMessage("test-stream", Map("hello" -> "world"))))

      read
        .concurrently(write)
        .take(1)
        .interruptAfter(3.seconds)
        .compile
        .lastOrError
        .map { read =>
          assertEquals(read.key, "test-stream")
          assertEquals(read.body, Map("hello" -> "world"))
        }
    }
  }

  test("concurrent read/write works") {

    withRedisStream[Unit](readWriteTest("test-stream", _))
  }

  test("concurrent read/write flakiness, in parallel") {
    (1 to 25)
      .map(i => IO.fromFuture(IO(withRedisStream[Unit](readWriteTest(s"test-stream-$i", _)))))
      .toList
      .parSequence
      .unsafeRunSync()
  }

  private def readWriteTest(streamName: String, stream: Streaming[fs2.Stream[IO, *], String, String]) = {
    val len  = 1000
    val msgs = List.fill(len)(generateMsg)

    val read = stream.read(Set(streamName), 1)
    val write = stream.append {
      fs2.Stream.emits(msgs).map(msg => XAddMessage(streamName, msg))
    }

    read
      .concurrently(write)
      .take(len.toLong)
      .interruptAfter(3.seconds)
      .compile
      .toList
      .map(_.map(_.body))
      .map(msgsRead => assertEquals(msgsRead, msgs))
  }

  private def generateMsg: Map[String, String] = {
    val size = 1 + Random.nextInt(2)
    (1 to size).map(_ => generateStr -> generateStr).toMap
  }

  private def generateStr: String = Random.alphanumeric.head.toString
}
