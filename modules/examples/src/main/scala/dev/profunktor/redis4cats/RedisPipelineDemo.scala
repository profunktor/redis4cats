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

import cats.effect._
import cats.implicits._
import dev.profunktor.redis4cats.algebra.RedisCommands
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.interpreter.Redis
import dev.profunktor.redis4cats.pipeline._
import scala.concurrent.duration._

object RedisPipelineDemo extends LoggerIOApp {

  import Demo._

  def program(implicit log: Log[IO]): IO[Unit] = {
    val key = "testp"

    val showResult: Int => Option[String] => IO[Unit] = n =>
      _.fold(putStrLn(s"Not found key $key-$n"))(s => putStrLn(s))

    val commandsApi: Resource[IO, RedisCommands[IO, String, String]] =
      for {
        uri <- Resource.liftF(RedisURI.make[IO](redisURI))
        client <- RedisClient[IO](uri)
        redis <- Redis[IO, String, String](client, stringCodec)
      } yield redis

    commandsApi
      .use { cmd =>
        def traversal(f: Int => IO[Unit]): IO[Unit] =
          List.range(0, 50).traverse(f).void

        val setters: IO[Unit] =
          traversal(n => cmd.set(s"$key-$n", (n * 2).toString).start.void)

        val getters: IO[Unit] =
          traversal(n => cmd.get(s"$key-$n").flatMap(showResult(n)))

        RedisPipeline(cmd).run(setters) *> IO.sleep(2.seconds) *> getters
      }
  }

}
