/*
 * Copyright 2018 Fs2 Redis
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

import cats.effect.IO
import cats.syntax.parallel._
import com.github.gvolpe.fs2redis.interpreter.connection.Fs2RedisClient
import com.github.gvolpe.fs2redis.interpreter.streams.Fs2Streaming
import com.github.gvolpe.fs2redis.model.{DefaultRedisCodec, StreamingMessage}
import fs2.StreamApp.ExitCode
import fs2.{Sink, Stream, StreamApp}
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.StringCodec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Random

object Fs2StreamingDemo extends StreamApp[IO] {

  private val redisURI    = RedisURI.create("redis://localhost")
  private val stringCodec = DefaultRedisCodec(StringCodec.UTF8)

  def sink(name: String): Sink[IO, String] = _.evalMap(x => IO(println(s"Subscriber: $name >> $x")))

  def putStrLn(str: String): IO[Unit] = IO(println(str))

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

  override def stream(args: List[String], requestShutdown: IO[Unit]): Stream[IO, ExitCode] =
    for {
      client    <- Fs2RedisClient.stream[IO](redisURI)
      streaming <- Fs2Streaming.mkStreamingConnection[IO, String, String](client, stringCodec, redisURI)
      source    = streaming.latest(Set(streamKey1, streamKey2))
      appender  = streaming.append
      rs <- Stream(
             source.evalMap(x => putStrLn(x.toString)),
             Stream.awakeEvery[IO](3.seconds) >> randomMessage.to(appender)
           ).join(2).drain
    } yield rs

}
