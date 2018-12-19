/*
 * Copyright 2018-2019 Fs2 Redis
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

package com.github.gvolpe.fs2redis

import cats.effect.{ ExitCode, IO, IOApp }
import cats.syntax.apply._
import cats.syntax.parallel._
import com.github.gvolpe.fs2redis.connection.Fs2RedisClient
import com.github.gvolpe.fs2redis.interpreter.streams.Fs2Streaming
import com.github.gvolpe.fs2redis.streams.StreamingMessage
import fs2.Stream

import scala.concurrent.duration._
import scala.util.Random

object Fs2StreamingDemo extends IOApp {

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

  def stream(args: List[String]): Stream[IO, Unit] =
    for {
      client <- Stream.resource(Fs2RedisClient[IO](redisURI))
      streaming <- Fs2Streaming.mkStreamingConnection[IO, String, String](client, stringCodec, redisURI)
      source   = streaming.read(Set(streamKey1, streamKey2))
      appender = streaming.append
      _ <- Stream(
            source.evalMap(x => putStrLn(x.toString)),
            Stream.awakeEvery[IO](3.seconds) >> randomMessage.to(appender)
          ).parJoin(2).drain
    } yield ()

  override def run(args: List[String]): IO[ExitCode] =
    stream(args).compile.drain *> IO.pure(ExitCode.Success)

}
