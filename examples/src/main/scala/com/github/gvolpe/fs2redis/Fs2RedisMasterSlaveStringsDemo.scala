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
import com.github.gvolpe.fs2redis.connection.Fs2RedisMasterSlave
import com.github.gvolpe.fs2redis.domain.Fs2RedisMasterSlaveConnection
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis
import io.lettuce.core.ReadFrom

object Fs2RedisMasterSlaveStringsDemo extends IOApp {

  import Demo._

  override def run(args: List[String]): IO[ExitCode] = {
    val usernameKey = "test"

    val showResult: Option[String] => IO[Unit] =
      _.fold(putStrLn(s"Not found key: $usernameKey"))(s => putStrLn(s))

    val connection: Resource[IO, Fs2RedisMasterSlaveConnection[String, String]] =
      Fs2RedisMasterSlave[IO, String, String](stringCodec, redisURI)(Some(ReadFrom.MASTER_PREFERRED))

    connection
      .use { conn =>
        Fs2Redis.masterSlave[IO, String, String](conn).flatMap { cmd =>
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
        }
      }
      .as(ExitCode.Success)
  }

}
