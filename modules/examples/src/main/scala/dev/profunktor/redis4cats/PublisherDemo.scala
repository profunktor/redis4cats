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
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data.RedisChannel
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.pubsub.PubSub
import fs2.Stream

import scala.concurrent.duration._
import scala.util.Random

object PublisherDemo extends LoggerIOApp {

  import Demo._

  private val eventsChannel = RedisChannel("events")

  def stream(implicit log: Log[IO]): Stream[IO, Unit] =
    for {
      uri <- Stream.eval(RedisURI.make[IO](redisURI))
      client <- Stream.resource(RedisClient[IO](uri))
      pubSub <- PubSub.mkPublisherConnection[IO, String, String](client, stringCodec)
      pub1 = pubSub.publish(eventsChannel)
      rs <- Stream(
             Stream.awakeEvery[IO](3.seconds) >> Stream.eval(IO(Random.nextInt(100).toString)).through(pub1),
             Stream.awakeEvery[IO](6.seconds) >> pubSub
                   .pubSubSubscriptions(eventsChannel)
                   .evalMap(putStrLn)
           ).parJoin(2).drain
    } yield rs

  def program(implicit log: Log[IO]): IO[Unit] =
    stream.compile.drain

}
