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
import cats.instances.list._
import cats.syntax.all._
import com.github.gvolpe.fs2redis._
import com.github.gvolpe.fs2redis.algebra.RedisCommands
import com.github.gvolpe.fs2redis.connection.Fs2RedisClient
import com.github.gvolpe.fs2redis.effect.Log
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis

object Fs2RedisTransactionsDemo extends LoggerIOApp {

  import Demo._

  def program(implicit log: Log[IO]): IO[Unit] = {
    val key1 = "test1"
    val key2 = "test2"

    val showResult: String => Option[String] => IO[Unit] = key =>
      _.fold(putStrLn(s"Not found key: $key"))(s => putStrLn(s))

    val commandsApi: Resource[IO, RedisCommands[IO, String, String]] =
      for {
        client <- Fs2RedisClient[IO](redisURI)
        redis <- Fs2Redis[IO, String, String](client, stringCodec, redisURI)
      } yield redis

    commandsApi
      .use { cmd =>
        val tx = RedisTransaction(cmd)

        val getters =
          cmd.get(key1).flatTap(showResult(key1)) *>
            cmd.get(key2).flatTap(showResult(key2))

        val setters =
          List(
            cmd.set(key1, "foo"),
            cmd.set(key2, "bar")
          ).traverse_(_.start)

        val failedSetters =
          List(
            cmd.set(key1, "qwe"),
            cmd.set(key2, "asd")
          ).traverse(_.start) *> IO.raiseError(new Exception("boom"))

        val tx1 = tx.run(setters)
        val tx2 = tx.run(failedSetters)

        getters *> tx1 *> tx2.attempt *> getters.void
      }
  }

}
