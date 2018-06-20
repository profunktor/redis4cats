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
import com.github.gvolpe.fs2redis.algebra.StringCommands
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis
import com.github.gvolpe.fs2redis.interpreter.connection.Fs2RedisClient

object Fs2RedisStringsDemo extends IOApp {

  import Demo._

  override def run(args: List[String]): IO[ExitCode] = {
    val usernameKey = "test"

    val showResult: Option[String] => IO[Unit] =
      _.fold(putStrLn(s"Not found key: $usernameKey"))(s => putStrLn(s))

    val commandsApi: Resource[IO, StringCommands[IO, String, String]] =
      for {
        client <- Fs2RedisClient[IO](redisURI)
        redis  <- Fs2Redis[IO, String, String](client, stringCodec, redisURI)
      } yield redis

    commandsApi.use { cmd =>
      for {
        x <- cmd.get(usernameKey)
        _ <- showResult(x)
        _ <- cmd.set(usernameKey, "some value")
        y <- cmd.get(usernameKey)
        _ <- showResult(y)
        _ <- cmd.setNx(usernameKey, "should not happen")
        w <- cmd.get(usernameKey)
        _ <- showResult(w)
        _ <- cmd.del(usernameKey)
        z <- cmd.get(usernameKey)
        _ <- showResult(z)
      } yield ()
    } *> IO.pure(ExitCode.Success)
  }

}
