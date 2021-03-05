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

import cats.data.EitherT
import cats.effect.{ IO, Resource }
import dev.profunktor.redis4cats.effect.Log.NoOp._

object RedisLiftKDemo extends LoggerIOApp {

  import Demo._

  val program: IO[Unit] = {
    val usernameKey = "test"

    val showResult: Option[String] => IO[Unit] =
      _.fold(putStrLn(s"Not found key: $usernameKey"))(s => putStrLn(s))

    val commandsApi: Resource[IO, RedisCommands[IO, String, String]] =
      Redis[IO].utf8(redisURI)

    commandsApi.use(
      _.liftK[EitherT[IO, String, *]]
        .get(usernameKey)
        .semiflatMap(x => showResult(x))
        .value
        .void
    )
  }

}
