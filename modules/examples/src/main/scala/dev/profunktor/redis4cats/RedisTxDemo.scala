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

import java.util.concurrent.TimeoutException

import cats.effect._
import cats.syntax.all._
import dev.profunktor.redis4cats.log4cats._
import dev.profunktor.redis4cats.tx.RedisTx
import dev.profunktor.redis4cats.transactions.TransactionDiscarded

object RedisTxDemo extends LoggerIOApp {

  import Demo._

  val program: IO[Unit] = {
    val key1 = "test1"
    val key2 = "test2"

    val showResult: String => Option[String] => IO[Unit] = key =>
      _.fold(putStrLn(s"Not found key: $key"))(s => putStrLn(s"$key: $s"))

    val commandsApi: Resource[IO, RedisCommands[IO, String, String]] =
      Redis[IO].utf8(redisURI)

    IO.ref(Map.empty[String, String]).flatMap { ref =>
      commandsApi.use { redis =>
        val getters =
          redis.get(key1).flatTap(showResult(key1)) *>
              redis.get(key2).flatTap(showResult(key2))

        def store(opt: Option[String], k: String): IO[Unit] =
          opt.traverse_(v => ref.update(_.updated(k, v)))

        val operations =
          List(
            redis.set(key1, "sad"),
            redis.set(key2, "windows"),
            redis.get(key1).flatMap(store(_, s"$key1-v1")),
            redis.set(key1, "nix"),
            redis.set(key2, "linux"),
            redis.get(key1).flatMap(store(_, s"$key1-v2"))
          )

        def displayGetResults: IO[Unit] =
          ref.get.flatMap(kv => IO.println(s"KV: $kv"))

        val prog =
          RedisTx.make(redis).use {
            _.run(operations)
              .handleErrorWith {
                case TransactionDiscarded =>
                  putStrLn("[Error] - Transaction Discarded")
                case _: TimeoutException =>
                  putStrLn("[Error] - Timeout")
                case e =>
                  putStrLn(s"[Error] - ${e.getMessage}")
              }
          }

        getters >> prog >> getters >> putStrLn("keep doing stuff...") >> displayGetResults
      }
    }
  }

}
