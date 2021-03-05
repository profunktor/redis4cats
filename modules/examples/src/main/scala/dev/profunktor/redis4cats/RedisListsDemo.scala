/*
 * Copyright 2018-2021 ProfunKtor
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

    commandsApi
      .use { cmd =>
        for {
          d <- cmd.rPush(testKey, "one", "two", "three")
          _ <- putStrLn(s"Length on Push: $d")
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
          c <- cmd.lInsertAfter(testKey, "two", "four")
          _ <- putStrLn(s"Length on Insert After: $c")
          e <- cmd.lInsertBefore(testKey, "four", "three")
          _ <- putStrLn(s"Length on Insert Before: $e")
          f <- cmd.lRange(testKey, 0, 10)
          _ <- putStrLn(s"Range: $f")
          _ <- cmd.lSet(testKey, 0, "four")
          g <- cmd.lRange(testKey, 0, 10)
          _ <- putStrLn(s"Range after Set: $g")
          h <- cmd.lRem(testKey, 2, "four")
          _ <- putStrLn(s"Removed: $h")
          i <- cmd.lRange(testKey, 0, 10)
          _ <- putStrLn(s"Range: $i")
        } yield ()
      }
  }

}
