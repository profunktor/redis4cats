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
import dev.profunktor.redis4cats.algebra.{ KeyCommands, StringCommands }
import dev.profunktor.redis4cats.effect.Log.NoOp._

object RedisKeysDemo extends LoggerIOApp {

  import Demo._

  val program: IO[Unit] = {
    val usernameKey = "test"

    val showResult: Option[String] => IO[Unit] =
      _.fold(IO.println(s"Not found key: $usernameKey"))(s => IO.println(s))

    val commandsApi: Resource[IO, KeyCommands[IO, String] with StringCommands[IO, String, String]] =
      Redis[IO].utf8(redisURI)

    commandsApi.use { redis =>
      for {
        x <- redis.get(usernameKey)
        _ <- showResult(x)
        _ <- redis.set(usernameKey, "some value")
        y <- redis.get(usernameKey)
        _ <- showResult(y)
        _ <- redis.setNx(usernameKey, "should not happen")
        w <- redis.get(usernameKey)
        v <- redis.del(usernameKey)
        _ <- IO.println(s"del: $v")
        z <- redis.get(usernameKey)
        _ <- showResult(z)
        _ <- showResult(w)
      } yield ()
    }
  }

}
