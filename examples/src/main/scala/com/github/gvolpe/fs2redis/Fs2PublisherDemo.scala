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
import com.github.gvolpe.fs2redis.interpreter.connection.Fs2RedisClient
import com.github.gvolpe.fs2redis.interpreter.pubsub.Fs2PubSub
import com.github.gvolpe.fs2redis.model.DefaultChannel
import fs2.StreamApp.ExitCode
import fs2.{Stream, StreamApp}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Random

object Fs2PublisherDemo extends StreamApp[IO] {

  import Demo._

  private val eventsChannel = DefaultChannel("events")

  override def stream(args: List[String], requestShutdown: IO[Unit]): Stream[IO, ExitCode] =
    for {
      client <- Fs2RedisClient.stream[IO](redisURI)
      pubSub <- Fs2PubSub.mkPublisherConnection[IO, String, String](client, stringCodec, redisURI)
      pub1   = pubSub.publish(eventsChannel)
      rs <- Stream(
             Stream.awakeEvery[IO](3.seconds) >> Stream.eval(IO(Random.nextInt(100).toString)) to pub1,
             Stream.awakeEvery[IO](6.seconds) >> pubSub
               .pubSubSubscriptions(eventsChannel)
               .evalMap(x => putStrLn(x.toString))
           ).join(2).drain
    } yield rs

}
