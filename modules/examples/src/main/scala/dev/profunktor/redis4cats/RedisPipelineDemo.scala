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

import cats.effect._
import dev.profunktor.redis4cats.effect.Log.NoOp._
import dev.profunktor.redis4cats.tx._

object RedisPipelineDemo extends LoggerIOApp {

  import Demo._

  val program: IO[Unit] = {
    val key1 = "testp1"
    val key2 = "testp2"

    val showResult: String => Option[String] => IO[Unit] = key =>
      _.fold(IO.println(s"Not found key: $key"))(s => IO.println(s"$key: $s"))

    val commandsApi: Resource[IO, RedisCommands[IO, String, String]] =
      Redis[IO].utf8(redisURI)

    commandsApi.use { redis =>
      val getters =
        redis.get(key1).flatTap(showResult(key1)) *>
          redis.get(key2).flatTap(showResult(key2))

      val ops = (store: TxStore[IO, String, Option[String]]) =>
        List(
          redis.set(key1, "noop"),
          redis.set(key2, "windows"),
          redis.get(key1).flatMap(store.set(s"$key1-v1")),
          redis.set(key1, "nix"),
          redis.set(key2, "linux"),
          redis.get(key1).flatMap(store.set(s"$key1-v2"))
        )

      val prog =
        redis
          .pipeline(ops)
          .flatMap(kv => IO.println(s"KV: $kv"))
          .recoverWith { case e =>
            IO.println(s"[Error] - ${e.getMessage}")
          }

      getters >> prog >> getters >> IO.println("keep doing stuff...")
    }
  }

}
