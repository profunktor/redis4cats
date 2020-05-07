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

import cats.effect.{ IO, Resource }
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.ListCommands
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.effect.Log

object RedisListsDemo extends LoggerIOApp {

  import Demo._

  def program(implicit log: Log[IO]): IO[Unit] = {
    val testKey = "listos"

    val commandsApi: Resource[IO, ListCommands[IO, String, String]] =
      for {
        uri <- Resource.liftF(RedisURI.make[IO](redisURI))
        client <- RedisClient[IO](uri)
        redis <- Redis[IO, String, String](client, stringCodec)
      } yield redis

    commandsApi
      .use { cmd =>
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
      }
  }

}
