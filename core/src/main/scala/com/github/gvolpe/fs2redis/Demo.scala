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
import com.github.gvolpe.fs2redis.util.JRFuture
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands
import io.lettuce.core.pubsub.{RedisPubSubListener, StatefulRedisPubSubConnection}
import io.lettuce.core.{RedisClient, RedisFuture, RedisURI}

object Demo extends App {

  val _redisUri = "redis://localhost"

  val client: RedisClient                          = RedisClient.create(_redisUri)
  val commands: RedisAsyncCommands[String, String] = client.connect.async

  val pubSubListener =
    new RedisPubSubListener[String, String] {
      override def message(channel: String, message: String): Unit =
        println(s"$channel >> $message")
      override def message(pattern: String, channel: String, message: String): Unit = ()
      override def psubscribed(pattern: String, count: Long): Unit                  = ()
      override def subscribed(channel: String, count: Long): Unit                   = ()
      override def unsubscribed(channel: String, count: Long): Unit                 = ()
      override def punsubscribed(pattern: String, count: Long): Unit                = ()
    }

  val pubSubConnection: StatefulRedisPubSubConnection[String, String] =
    client.connectPubSub(RedisURI.create(_redisUri))
  pubSubConnection.addListener(pubSubListener)

  def putStrLn(str: String): IO[Unit] = IO(println(str))

//  val result: RedisFuture[String] = commands.get("username")

  val ioa: IO[RedisFuture[String]] = IO(commands.get("username"))

  JRFuture(ioa).flatMap(x => putStrLn(x)).unsafeRunSync()

  val pubSub: RedisPubSubAsyncCommands[String, String] = pubSubConnection.async()

  pubSub.subscribe("events")

//  Thread.sleep(1000 * 2)

//  pubSub.publish("events", "hello world!") // Cannot subscribe and publish with the same connection

  Thread.sleep(1000 * 2)

  client.shutdown()

}
