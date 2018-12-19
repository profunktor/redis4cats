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

import cats.effect.{ ExitCode, IO, IOApp, Resource }
import cats.syntax.all._
import com.github.gvolpe.fs2redis.algebra.ListCommands
import com.github.gvolpe.fs2redis.connection.Fs2RedisClient
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis

object Fs2RedisListsDemo extends IOApp {

  import Demo._

  override def run(args: List[String]): IO[ExitCode] = {
    val testKey = "listos"

    val commandsApi: Resource[IO, ListCommands[IO, String, String]] =
      for {
        client <- Fs2RedisClient[IO](redisURI)
        redis <- Fs2Redis[IO, String, String](client, stringCodec, redisURI)
      } yield redis

    commandsApi.use { cmd =>
      for {
        _ <- cmd.rPush(testKey, "one", "two", "three")
        x <- cmd.lRange(testKey, 0, 10)
        _ <- putStrLn(s"Range: $x")
        y <- cmd.lLen(testKey)
        _ <- putStrLn(s"Length: $y")
        a <- cmd.lPop(testKey)
        _ <- putStrLn(s"Left Pop: $a")
        b <- cmd.rPop(testKey)
        _ <- putStrLn(s"Right Pop: $b")
        z <- cmd.lRange(testKey, 0, 10)
        _ <- putStrLn(s"Range: $z")
      } yield ()
    } *> IO.pure(ExitCode.Success)
  }

}
