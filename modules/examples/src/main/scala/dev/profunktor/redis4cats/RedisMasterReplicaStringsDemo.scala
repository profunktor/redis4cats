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
import cats.syntax.all._
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data.ReadFrom
import dev.profunktor.redis4cats.effect.Log.NoOp._

object RedisMasterReplicaStringsDemo extends LoggerIOApp {

  import Demo._

  val program: IO[Unit] = {
    val usernameKey = "test"

    val showResult: Option[String] => IO[Unit] =
      _.fold(IO.println(s"Not found key: $usernameKey"))(s => IO.println(s))

    val masterUri: String  = "redis://localhost"
    val replicaUri: String = "redis://localhost:6380"

    val connection: Resource[IO, RedisCommands[IO, String, String]] =
      for {
        uri1 <- Resource.eval(RedisURI.make[IO](masterUri))
        uri2 <- Resource.eval(RedisURI.make[IO](replicaUri))
        conn <- RedisMasterReplica[IO].make(stringCodec, uri1, uri2)(Some(ReadFrom.Replica))
        redis <- Redis[IO].masterReplica(conn)
      } yield redis

    connection.use { redis =>
      for {
        i <- redis.info("replication")
        _ <- IO.println("SERVER INFO\n")
        _ <- i.toList.traverse_(kv => IO.println(kv))
        _ <- IO.println("----------\n")
        x <- redis.get(usernameKey)
        _ <- showResult(x)
        _ <- redis.set(usernameKey, "some value")
        y <- redis.get(usernameKey)
        _ <- showResult(y)
        _ <- redis.setNx(usernameKey, "should not happen")
        w <- redis.get(usernameKey)
        _ <- showResult(w)
        _ <- redis.del(usernameKey)
        z <- redis.get(usernameKey)
        _ <- showResult(z)
      } yield ()
    }
  }

}
