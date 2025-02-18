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
import dev.profunktor.redis4cats.algebra.StringCommands
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.effect.Log.NoOp._
import io.lettuce.core.RedisCommandExecutionException

object RedisStringsDemo extends LoggerIOApp {
  import Demo._

  val usernameKey = "test"
  val numericKey  = "numeric"

  val showResult: Option[String] => IO[Unit] =
    _.fold(IO.println(s"Not found key: $usernameKey"))(IO.println)

  // simple strings program
  def p1(redis: StringCommands[IO, String, String]): IO[Unit] =
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

  // proof that you can still get it wrong with `incr` and `decr`, even if type-safe
  def p2(
      redis: StringCommands[IO, String, String],
      redisN: StringCommands[IO, String, Long]
  ): IO[Unit] =
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

  val program: IO[Unit] = {
    val res =
      for {
        cli <- RedisClient[IO].from(redisURI)
        rd1 <- Redis[IO].fromClient(cli, stringCodec)
        rd2 <- Redis[IO].fromClient(cli, longCodec)
      } yield rd1 -> rd2

    res.use { case (rd1, rd2) =>
      p1(rd1) *> p2(rd1, rd2)
    }
  }

}
