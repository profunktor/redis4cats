/*
 * Copyright 2018-2020 ProfunKtor
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
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.streams.RedisStream
import dev.profunktor.redis4cats.streams.data.StreamingMessage
import fs2.Stream

import scala.concurrent.duration._
import scala.util.Random

object StreamingDemo extends LoggerIOApp {

  import Demo._

  private val streamKey1 = "demo"
  private val streamKey2 = "users"

  def randomMessage: Stream[IO, StreamingMessage[String, String]] = Stream.eval {
    val rndKey   = IO(Random.nextInt(1000).toString)
    val rndValue = IO(Random.nextString(10))
    (rndKey, rndValue).parMapN {
      case (k, v) =>
        StreamingMessage(streamKey1, Map(k -> v))
    }
  }

  def stream(implicit log: Log[IO]): Stream[IO, Unit] =
    for {
      uri <- Stream.eval(RedisURI.make[IO](redisURI))
      client <- Stream.resource(RedisClient[IO](uri))
      streaming <- RedisStream.mkStreamingConnection[IO, String, String](client, stringCodec)
      source   = streaming.read(Set(streamKey1, streamKey2))
      appender = streaming.append
      rs <- Stream(
             source.evalMap(putStrLn),
             Stream.awakeEvery[IO](3.seconds) >> randomMessage.through(appender)
           ).parJoin(2).drain
    } yield rs

  def program(implicit log: Log[IO]): IO[Unit] =
    stream.compile.drain

}
