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

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all._
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis
import com.github.gvolpe.fs2redis.interpreter.connection.Fs2RedisClient
import com.github.gvolpe.fs2redis.model.DefaultRedisCodec
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.StringCodec

object Fs2RedisBasicDemo extends IOApp {

  private val redisURI    = RedisURI.create("redis://localhost")
  private val stringCodec = DefaultRedisCodec(StringCodec.UTF8)

  def putStrLn(str: String): IO[Unit] = IO(println(str))

  override def run(args: List[String]): IO[ExitCode] = {
    val fs2RedisCommand: Resource[IO, Fs2Redis[IO, String, String]] = for {
      client <- Fs2RedisClient[IO](redisURI)
      redis  <- Fs2Redis[IO, String, String](client, stringCodec, redisURI)
    } yield redis

    val usernameKey = "test"

    val showResult: Option[String] => IO[Unit] =
      _.fold(putStrLn(s"Not found key: $usernameKey"))(s => putStrLn(s))

    fs2RedisCommand.use { cmd =>
      for {
        x <- cmd.get(usernameKey)
        _ <- showResult(x)
        _ <- cmd.set(usernameKey, "some value")
        _ <- showResult(x)
        _ <- cmd.del(usernameKey)
        _ <- showResult(x)
      } yield ()
    } *> IO.pure(ExitCode.Success)
  }

}
