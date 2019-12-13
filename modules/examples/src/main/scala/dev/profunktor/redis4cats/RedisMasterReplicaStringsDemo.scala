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
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.domain.ReadFrom
import dev.profunktor.redis4cats.connection.RedisMasterReplica
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.interpreter.Redis

object RedisMasterReplicaStringsDemo extends LoggerIOApp {

  import Demo._

  def program(implicit log: Log[IO]): IO[Unit] = {
    val usernameKey = "test"

    val showResult: Option[String] => IO[Unit] =
      _.fold(putStrLn(s"Not found key: $usernameKey"))(s => putStrLn(s))

    val connection: Resource[IO, RedisMasterReplica[String, String]] =
      Resource.liftF(RedisURI.make[IO](redisURI)).flatMap { uri =>
        RedisMasterReplica[IO, String, String](stringCodec, uri)(Some(ReadFrom.MasterPreferred))
      }

    connection
      .use { conn =>
        Redis.masterReplica[IO, String, String](conn).flatMap { cmd =>
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

}
