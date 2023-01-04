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
import dev.profunktor.redis4cats.algebra.HashCommands
import dev.profunktor.redis4cats.effect.Log.NoOp._

object RedisHashesDemo extends LoggerIOApp {

  import Demo._

  val program: IO[Unit] = {
    val testKey   = "foo"
    val testField = "bar"

    val showResult: Option[String] => IO[Unit] =
      _.fold(IO.println(s"Not found key: $testKey | field: $testField"))(s => IO.println(s))

    val commandsApi: Resource[IO, HashCommands[IO, String, String]] =
      Redis[IO].utf8(redisURI)

    commandsApi.use { redis =>
      for {
        x <- redis.hGet(testKey, testField)
        _ <- showResult(x)
        _ <- redis.hSet(testKey, testField, "some value")
        y <- redis.hGet(testKey, testField)
        _ <- showResult(y)
        _ <- redis.hSetNx(testKey, testField, "should not happen")
        w <- redis.hGet(testKey, testField)
        _ <- showResult(w)
        _ <- redis.hDel(testKey, testField)
        z <- redis.hGet(testKey, testField)
        _ <- showResult(z)
      } yield ()
    }
  }

}
