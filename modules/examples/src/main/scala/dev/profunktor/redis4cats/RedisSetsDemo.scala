/*
 * Copyright 2018-2019 ProfunKtor
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

import cats.effect.{ IO, Resource }
import dev.profunktor.redis4cats.algebra.SetCommands
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.interpreter.Redis

object RedisSetsDemo extends LoggerIOApp {

  import Demo._

  def program(implicit log: Log[IO]): IO[Unit] = {
    val testKey = "foos"

    val showResult: Set[String] => IO[Unit] = x => putStrLn(s"$testKey members: $x")

    val commandsApi: Resource[IO, SetCommands[IO, String, String]] =
      for {
        uri <- Resource.liftF(RedisURI.make[IO](redisURI))
        client <- RedisClient[IO](uri)
        redis <- Redis[IO, String, String](client, stringCodec)
      } yield redis

    commandsApi
      .use { cmd =>
        for {
          x <- cmd.sMembers(testKey)
          _ <- showResult(x)
          _ <- cmd.sAdd(testKey, "set value")
          y <- cmd.sMembers(testKey)
          _ <- showResult(y)
          _ <- cmd.sCard(testKey).flatMap(s => putStrLn(s"size: $s"))
          _ <- cmd.sRem("non-existing", "random")
          w <- cmd.sMembers(testKey)
          _ <- showResult(w)
          _ <- cmd.sRem(testKey, "set value")
          z <- cmd.sMembers(testKey)
          _ <- showResult(z)
          _ <- cmd.sCard(testKey).flatMap(s => putStrLn(s"size: $s"))
        } yield ()
      }
  }

}
