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
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.{RedisClient, RedisURI}

import scala.concurrent.ExecutionContext.Implicits.global

object Demo extends App {

  val redisUri = "redis://localhost"

  val client: RedisClient = RedisClient.create(redisUri)

  val connection: StatefulRedisConnection[String, String] = client.connect

  val commands: RedisAsyncCommands[String, String] = connection.async

  val cmd = client.connectAsync[String, String](StringCodec.UTF8, RedisURI.create(redisUri))

  def putStrLn(str: String): IO[Unit] = IO(println(str))

  val program =
    for {
      _ <- IO(connection.async().set("username", "admin"))
      n <- IO(connection.async().get("username"))
    } yield n

  JRFuture(program).flatMap(x => putStrLn(x)).unsafeRunSync()

  Thread.sleep(1000 * 2)

  client.shutdown()

}
