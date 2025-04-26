/*
 * Copyright 2018-2025 ProfunKtor
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
import dev.profunktor.redis4cats.algebra.ListCommands
import dev.profunktor.redis4cats.effect.Log.NoOp._

object RedisListsDemo extends LoggerIOApp {

  import Demo._

  val program: IO[Unit] = {
    val testKey = "listos"

    val commandsApi: Resource[IO, ListCommands[IO, String, String]] =
      Redis[IO].utf8(redisURI)

    commandsApi.use { redis =>
      for {
        d <- redis.rPush(testKey, "one", "two", "three")
        _ <- IO.println(s"Length on Push: $d")
        x <- redis.lRange(testKey, 0, 10)
        _ <- IO.println(s"Range: $x")
        y <- redis.lLen(testKey)
        _ <- IO.println(s"Length: $y")
        a <- redis.lPop(testKey)
        _ <- IO.println(s"Left Pop: $a")
        b <- redis.rPop(testKey)
        _ <- IO.println(s"Right Pop: $b")
        z <- redis.lRange(testKey, 0, 10)
        _ <- IO.println(s"Range: $z")
        c <- redis.lInsertAfter(testKey, "two", "four")
        _ <- IO.println(s"Length on Insert After: $c")
        e <- redis.lInsertBefore(testKey, "four", "three")
        _ <- IO.println(s"Length on Insert Before: $e")
        f <- redis.lRange(testKey, 0, 10)
        _ <- IO.println(s"Range: $f")
        _ <- redis.lSet(testKey, 0, "four")
        g <- redis.lRange(testKey, 0, 10)
        _ <- IO.println(s"Range after Set: $g")
        h <- redis.lRem(testKey, 2, "four")
        _ <- IO.println(s"Removed: $h")
        i <- redis.lRange(testKey, 0, 10)
        _ <- IO.println(s"Range: $i")
      } yield ()
    }
  }

}
