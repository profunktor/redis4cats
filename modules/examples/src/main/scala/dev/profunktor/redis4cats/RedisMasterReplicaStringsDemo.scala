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
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data.ReadFrom
import dev.profunktor.redis4cats.effect.Log.NoOp._

object RedisMasterReplicaStringsDemo extends LoggerIOApp {

  import Demo._

  val program: IO[Unit] = {
    val usernameKey = "test"

    val showResult: Option[String] => IO[Unit] =
      _.fold(putStrLn(s"Not found key: $usernameKey"))(s => putStrLn(s))

    val connection: Resource[IO, RedisCommands[IO, String, String]] =
      for {
        uri <- Resource.liftF(RedisURI.make[IO](redisURI))
        conn <- RedisMasterReplica[IO].make(stringCodec, uri)(Some(ReadFrom.UpstreamPreferred))
        cmds <- Redis[IO].masterReplica(conn)
      } yield cmds

    connection
      .use { cmd =>
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

}
