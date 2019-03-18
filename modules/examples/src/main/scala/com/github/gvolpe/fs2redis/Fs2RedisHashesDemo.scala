/*
 * Copyright 2018-2019 Gabriel Volpe
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

package com.github.gvolpe.fs2redis

import cats.effect.{ IO, Resource }
import cats.syntax.functor._
import com.github.gvolpe.fs2redis.algebra.HashCommands
import com.github.gvolpe.fs2redis.connection._
import com.github.gvolpe.fs2redis.effect.Log
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis

object Fs2RedisHashesDemo extends LoggerIOApp {

  import Demo._

  def program(implicit log: Log[IO]): IO[Unit] = {
    val testKey   = "foo"
    val testField = "bar"

    val showResult: Option[String] => IO[Unit] =
      _.fold(putStrLn(s"Not found key: $testKey | field: $testField"))(s => putStrLn(s))

    val commandsApi: Resource[IO, HashCommands[IO, String, String]] =
      for {
        uri <- Resource.liftF(Fs2RedisURI.make[IO](redisURI))
        client <- Fs2RedisClient[IO](uri)
        redis <- Fs2Redis[IO, String, String](client, stringCodec, uri)
      } yield redis

    commandsApi
      .use { cmd =>
        for {
          x <- cmd.hGet(testKey, testField)
          _ <- showResult(x)
          _ <- cmd.hSet(testKey, testField, "some value")
          y <- cmd.hGet(testKey, testField)
          _ <- showResult(y)
          _ <- cmd.hSetNx(testKey, testField, "should not happen")
          w <- cmd.hGet(testKey, testField)
          _ <- showResult(w)
          _ <- cmd.hDel(testKey, testField)
          z <- cmd.hGet(testKey, testField)
          _ <- showResult(z)
        } yield ()
      }
  }

}
