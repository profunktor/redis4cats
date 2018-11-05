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

import cats.effect.{ ExitCode, IO, IOApp }
import cats.syntax.apply._
import com.github.gvolpe.fs2redis.connection.Fs2RedisClient
import com.github.gvolpe.fs2redis.interpreter.pubsub.Fs2PubSub
import com.github.gvolpe.fs2redis.domain.DefaultChannel
import fs2.{ Sink, Stream }

import scala.concurrent.duration._
import scala.util.Random

object Fs2PubSubDemo extends IOApp {

  import Demo._

  private val eventsChannel = DefaultChannel("events")
  private val gamesChannel  = DefaultChannel("games")

  def sink(name: String): Sink[IO, String] = _.evalMap(x => putStrLn(s"Subscriber: $name >> $x"))

  def stream(args: List[String]): Stream[IO, Unit] =
    for {
      client <- Stream.resource(Fs2RedisClient[IO](redisURI))
      pubSub <- Fs2PubSub.mkPubSubConnection[IO, String, String](client, stringCodec, redisURI)
      sub1 = pubSub.subscribe(eventsChannel)
      sub2 = pubSub.subscribe(gamesChannel)
      pub1 = pubSub.publish(eventsChannel)
      pub2 = pubSub.publish(gamesChannel)
      _ <- Stream(
            sub1 to sink("#events"),
            sub2 to sink("#games"),
            Stream.awakeEvery[IO](3.seconds) >> Stream.eval(IO(Random.nextInt(100).toString)) to pub1,
            Stream.awakeEvery[IO](5.seconds) >> Stream.emit("Pac-Man!") to pub2,
            Stream.awakeDelay[IO](11.seconds) >> pubSub.unsubscribe(gamesChannel),
            Stream.awakeEvery[IO](6.seconds) >> pubSub
              .pubSubSubscriptions(List(eventsChannel, gamesChannel))
              .evalMap(x => putStrLn(x.toString))
          ).parJoin(6).drain
    } yield ()

  override def run(args: List[String]): IO[ExitCode] =
    stream(args).compile.drain *> IO.pure(ExitCode.Success)

}
