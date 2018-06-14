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
import com.github.gvolpe.fs2redis.interpreter.{Fs2PubSub, Fs2RedisClient}
import com.github.gvolpe.fs2redis.model.{DefaultChannel, DefaultRedisCodec}
import fs2.StreamApp.ExitCode
import fs2.{Sink, Stream, StreamApp}
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.StringCodec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Random

object Fs2RedisDemo extends StreamApp[IO] {

  private val redisURI    = RedisURI.create("redis://localhost")
  private val stringCodec = DefaultRedisCodec(StringCodec.UTF8)

  private val eventsChannel = DefaultChannel("events")
  private val gamesChannel  = DefaultChannel("games")

  def sink(name: String): Sink[IO, String] = _.evalMap(x => IO(println(s"Subscriber: $name >> $x")))

  override def stream(args: List[String], requestShutdown: IO[Unit]): Stream[IO, ExitCode] =
    for {
      client    <- Fs2RedisClient[IO](redisURI)
      pubSub    = new Fs2PubSub[IO](client)
      pubSubCmd <- pubSub.createPubSubConnection(stringCodec, redisURI)
      sub1      = pubSubCmd.subscribe(eventsChannel)
      sub2      = pubSubCmd.subscribe(gamesChannel)
      pub1      = pubSubCmd.publish(eventsChannel)
      pub2      = pubSubCmd.publish(gamesChannel)
      rs <- Stream(
             sub1 to sink("#events"),
             sub2 to sink("#games"),
             Stream.awakeEvery[IO](3.seconds) >> Stream.eval(IO(Random.nextInt(100).toString)) to pub1,
             Stream.awakeEvery[IO](5.seconds) >> Stream.emit("Pac-Man!") to pub2,
             Stream.awakeDelay[IO](11.seconds) >> pubSubCmd.unsubscribe(gamesChannel),
             Stream.awakeEvery[IO](6.seconds) >> pubSubCmd
               .pubSubSubscriptions(List(eventsChannel, gamesChannel))
               .evalMap(x => IO(println(x)))
           ).join(6).drain
    } yield rs

}
