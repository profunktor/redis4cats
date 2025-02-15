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

import cats.effect.IO
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.effect.Log.NoOp._
import dev.profunktor.redis4cats.Redis.Pool._
import io.lettuce.core.RedisCommandExecutionException
import org.typelevel.keypool.KeyPool
import fs2.Stream

object RedisPoolDemo extends LoggerIOApp {
  import Demo._

  val usernameKey = "test"
  val numericKey  = "numeric"

  val showResult: Option[String] => IO[Unit] =
    _.fold(IO.println(s"Not found key: $usernameKey"))(IO.println)

  // simple strings program
  def p1(stringPool: KeyPool[IO, Unit, RedisCommands[IO, String, String]]): IO[Unit] =
    stringPool.withRedisCommands { redis =>
      for {
        x <- redis.get(usernameKey)
        _ <- showResult(x)
        _ <- redis.set(usernameKey, "some value")
        y <- redis.get(usernameKey)
        _ <- showResult(y)
        _ <- redis.setNx(usernameKey, "should not happen")
        w <- redis.get(usernameKey)
        _ <- showResult(w)
      } yield ()
    }

  // proof that you can still get it wrong with `incr` and `decr`, even if type-safe
  def p2(
      stringPool: KeyPool[IO, Unit, RedisCommands[IO, String, String]],
      longPool: KeyPool[IO, Unit, RedisCommands[IO, String, Long]]
  ): IO[Unit] =
    stringPool.withRedisCommands { redis =>
      longPool.withRedisCommands { redisN =>
        for {
          x <- redis.get(numericKey)
          _ <- showResult(x)
          _ <- redis.set(numericKey, "not a number")
          y <- redis.get(numericKey)
          _ <- showResult(y)
          _ <- redisN.incr(numericKey).attempt.flatMap {
                 case Left(e: RedisCommandExecutionException) =>
                   IO(assert(e.getMessage == "ERR value is not an integer or out of range"))
                 case _ =>
                   IO.raiseError(new Exception("Expected error"))
               }
          w <- redis.get(numericKey)
          _ <- showResult(w)
        } yield ()
      }
    }

  val program: IO[Unit] = {
    val res: Stream[IO, Unit] =
      for {
        cli <- Stream.resource(RedisClient[IO].from(redisURI))
        rd1 <- Stream.resource(Redis[IO].pooled(cli, stringCodec))
        rd2 <- Stream.resource(Redis[IO].pooled(cli, longCodec))
        _ <- Stream.eval(p1(rd1) *> p2(rd1, rd2))
      } yield ()

    res.compile.lastOrError

  }

}
