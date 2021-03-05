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
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data.RedisChannel
import dev.profunktor.redis4cats.effect.Log.NoOp._
import dev.profunktor.redis4cats.pubsub.PubSub
import fs2.{ Pipe, Stream }
import scala.concurrent.duration._
import scala.util.Random

object PubSubDemo extends LoggerIOApp {

  import Demo._

  private val eventsChannel = RedisChannel("events")
  private val gamesChannel  = RedisChannel("games")

  def sink(name: String): Pipe[IO, String, Unit] =
    _.evalMap(x => putStrLn(s"Subscriber: $name >> $x"))

  val stream: Stream[IO, Unit] =
    for {
      client <- Stream.resource(RedisClient[IO].from(redisURI))
      pubSub <- Stream.resource(PubSub.mkPubSubConnection[IO, String, String](client, stringCodec))
      sub1 = pubSub.subscribe(eventsChannel)
      sub2 = pubSub.subscribe(gamesChannel)
      pub1 = pubSub.publish(eventsChannel)
      pub2 = pubSub.publish(gamesChannel)
      rs <- Stream(
             sub1.through(sink("#events")),
             sub2.through(sink("#games")),
             Stream.awakeEvery[IO](3.seconds) >> Stream.eval(IO(Random.nextInt(100).toString)).through(pub1),
             Stream.awakeEvery[IO](5.seconds) >> Stream.emit("Pac-Man!").through(pub2),
             Stream.awakeDelay[IO](11.seconds) >> pubSub.unsubscribe(gamesChannel),
             Stream.awakeEvery[IO](6.seconds) >> pubSub
                   .pubSubSubscriptions(List(eventsChannel, gamesChannel))
                   .evalMap(putStrLn)
           ).parJoin(6).drain
    } yield rs

  val program: IO[Unit] =
    stream.compile.drain

}
