/*
 * Copyright 2018-2025 ProfunKtor
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
import cats.syntax.parallel._
import dev.profunktor.redis4cats.effect.Log.NoOp._
import dev.profunktor.redis4cats.effects.XReadOffsets
import dev.profunktor.redis4cats.streams.RedisStream
import dev.profunktor.redis4cats.streams.data.XAddMessage
import fs2.Stream

import scala.concurrent.duration._
import scala.util.Random

object StreamingDemo extends LoggerIOApp {

  import Demo._

  private val streamKey1 = "demo"
  private val streamKey2 = "users"

  def randomMessage: Stream[IO, XAddMessage[String, String]] = Stream.evals {
    val rndKey   = IO(Random.nextInt(1000).toString)
    val rndValue = IO(Random.nextString(10))
    (rndKey, rndValue).parMapN { case (k, v) =>
      List(
        XAddMessage(streamKey1, Map(k -> v)),
        XAddMessage(streamKey2, Map(k -> v))
      )
    }
  }

  private val readStream: Stream[IO, Unit] =
    for {
      redis <- Stream.resource(Redis[IO].simple(redisURI, stringCodec))
      streaming = RedisStream[IO, String, String](redis)
      message <- streaming.read(XReadOffsets.all(streamKey1, streamKey2))
      _ <- Stream.eval(IO.println(message))
    } yield ()

  private val writeStream: Stream[IO, Unit] =
    for {
      redis <- Stream.resource(Redis[IO].simple(redisURI, stringCodec))
      streaming = RedisStream[IO, String, String](redis)
      _ <- Stream.awakeEvery[IO](2.seconds)
      _ <- randomMessage.through(streaming.append)
    } yield ()

  val program: IO[Unit] =
    readStream
      .concurrently(writeStream)
      .interruptAfter(5.seconds)
      .compile
      .drain

}
