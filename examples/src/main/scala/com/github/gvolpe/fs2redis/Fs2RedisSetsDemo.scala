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

import cats.effect.{ ExitCode, IO, IOApp, Resource }
import cats.syntax.all._
import com.github.gvolpe.fs2redis.algebra.SetCommands
import com.github.gvolpe.fs2redis.connection.Fs2RedisClient
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis

object Fs2RedisSetsDemo extends IOApp {

  import Demo._

  override def run(args: List[String]): IO[ExitCode] = {
    val testKey = "foos"

    val showResult: Set[String] => IO[Unit] = x => putStrLn(s"$testKey members: $x")

    val commandsApi: Resource[IO, SetCommands[IO, String, String]] =
      for {
        client <- Fs2RedisClient[IO](redisURI)
        redis <- Fs2Redis[IO, String, String](client, stringCodec, redisURI)
      } yield redis

    commandsApi.use { cmd =>
      for {
        x <- cmd.sMembers(testKey)
        _ <- showResult(x)
        _ <- cmd.sAdd(testKey, "set value")
        y <- cmd.sMembers(testKey)
        _ <- showResult(y)
        _ <- cmd.sCard(testKey).flatMap(s => putStrLn(s"size: ${s.toString}"))
        _ <- cmd.sRem("non-existing", "random")
        w <- cmd.sMembers(testKey)
        _ <- showResult(w)
        _ <- cmd.sRem(testKey, "set value")
        z <- cmd.sMembers(testKey)
        _ <- showResult(z)
        _ <- cmd.sCard(testKey).flatMap(s => putStrLn(s"size: ${s.toString}"))
      } yield ()
    } *> IO.pure(ExitCode.Success)
  }

}
