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
        .interruptAfter(1.seconds)
        .compile
        .lastOrError
        .map { read =>
          assertEquals(read.key, "test-stream")
          assertEquals(read.body, Map("hello" -> "world"))
        }
    }
  }

  private def generateStr: String = {
    val len = Random.nextInt(20) + 10
    Random.alphanumeric.take(len).mkString
  }

  private def generateMsg: Map[String, String] = {
    val size = Random.nextInt(5) + 5
    (0 until size).map(_ => generateStr -> generateStr).toMap
  }

  test("write then read works") {
    withRedisStream[Unit] { stream =>
      val len  = 100
      val msgs = List.fill(len)(generateMsg)

      val streamName = "test-stream"
      val read       = stream.read(Set(streamName), 1)
      val write = stream.append {
        fs2.Stream.emits(msgs).evalMap(msg => IO(XAddMessage(streamName, msg)))
      }

      write.compile.drain.flatMap { _ =>
        read
          .take(len.toLong)
          .interruptAfter(10.seconds)
          .compile
          .toList
          .map(_.map(readmsg => readmsg.body))
          .map(msgsRead => assertEquals(msgsRead, msgs))
      }
    }
  }
}
