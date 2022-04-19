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
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.log4cats._
import dev.profunktor.redis4cats.tx.{ RedisTx, TransactionDiscarded }

object RedisTxDemo extends LoggerIOApp {

  import Demo._

  val program: IO[Unit] = {
    val key1 = "test1"
    val key2 = "test2"
    val key3 = "test3"

    val showResult: String => Option[String] => IO[Unit] = key =>
      _.fold(putStrLn(s"Not found key: $key"))(s => putStrLn(s"$key: $s"))

    val mkClient: Resource[IO, RedisClient] =
      RedisClient[IO].from(redisURI)

    def mkRedis(cli: RedisClient): Resource[IO, RedisCommands[IO, String, String]] =
      Redis[IO].fromClient(cli, RedisCodec.Utf8)

    def prog[A](
        tx: RedisTx[IO],
        ops: RedisTx.Store[IO, String, A] => List[IO[Unit]]
    ): IO[Unit] =
      tx.run(ops) // or tx.run_(ops) to discard the result
        .flatMap(kv => IO.println(s"KV: $kv"))
        .handleErrorWith {
          case TransactionDiscarded =>
            putStrLn("[Error] - Transaction Discarded")
          case e =>
            putStrLn(s"[Error] - ${e.getMessage}")
        }

    // Running two concurrent transactions (needs two different RedisCommands)
    mkClient.use { cli =>
      val p1 = mkRedis(cli).use { redis =>
        RedisTx.make(redis).use { tx =>
          val getters =
            redis.get(key1).flatTap(showResult(key1)) *>
                redis.get(key2).flatTap(showResult(key2))

          // it is not possible to mix different stores. In case of needing to preserve values
          // of other types, you'd need to use a local Ref or so.
          val ops = (store: RedisTx.Store[IO, String, Option[String]]) =>
            List(
              redis.set(key1, "sad"),
              redis.set(key2, "windows"),
              redis.get(key1).flatMap(store.set(s"$key1-v1")),
              redis.set(key1, "nix"),
              redis.set(key2, "linux"),
              redis.get(key1).flatMap(store.set(s"$key1-v2"))
            )

          getters >> prog(tx, ops) >> getters >> putStrLn("keep doing stuff...")
        }
      }

      val p2 = mkRedis(cli).use { redis =>
        RedisTx.make(redis).use { tx =>
          val ops = (store: RedisTx.Store[IO, String, Long]) =>
            List(
              redis.set("yo", "wat"),
              redis.incr(key3).flatMap(store.set(s"$key3-v1")),
              redis.incr(key3).flatMap(store.set(s"$key3-v2")),
              redis.set("wat", "yo")
            )

          prog(tx, ops)
        }
      }

      p1 &> p2
    }
  }

}
