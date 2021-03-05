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
import cats.syntax.all._
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.hlist._
import dev.profunktor.redis4cats.log4cats._
import dev.profunktor.redis4cats.transactions._
import java.util.concurrent.TimeoutException

object ConcurrentTransactionsDemo extends LoggerIOApp {

  import Demo._

  def program: IO[Unit] = {
    val key1 = "test1"
    val key2 = "test2"

    val showResult: String => Option[String] => IO[Unit] = key =>
      _.fold(Log[IO].info(s"Key not found: $key"))(s => Log[IO].info(s"$key: $s"))

    val mkRedis: Resource[IO, RedisCommands[IO, String, String]] =
      RedisClient[IO].from(redisURI).flatMap(cli => Redis[IO].fromClient(cli, RedisCodec.Utf8))

    def txProgram(v1: String, v2: String) =
      mkRedis
        .use { cmd =>
          val getters =
            cmd.get(key1).flatTap(showResult(key1)) *>
                cmd.get(key2).flatTap(showResult(key2))

          val operations =
            cmd.set(key1, "sad") :: cmd.set(key2, "windows") :: cmd.get(key1) ::
                cmd.set(key1, v1) :: cmd.set(key2, v2) :: cmd.get(key1) :: HNil

          val prog: IO[Unit] =
            RedisTransaction(cmd)
              .filterExec(operations)
              .flatMap {
                case res1 ~: res2 ~: HNil =>
                  Log[IO].info(s"res1: $res1, res2: $res2")
              }
              .onError {
                case TransactionAborted =>
                  Log[IO].error("[Error] - Transaction Aborted")
                case TransactionDiscarded =>
                  Log[IO].error("[Error] - Transaction Discarded")
                case _: TimeoutException =>
                  Log[IO].error("[Error] - Timeout")
              }

          val watching =
            cmd.watch(key1, key2)

          getters >> watching >> prog >> getters >> Log[IO].info("keep doing stuff...")
        }

    // Only the first transaction will be successful. The second one will be discarded.
    //IO.race(txProgram("nix", "linux"), txProgram("foo", "bar")).void

    def retriableTx: IO[Unit] =
      txProgram("foo", "bar").handleErrorWith {
        case TransactionDiscarded => retriableTx
      }.uncancelable

    // The first transaction will be successful but ultimately, the second transaction will retry and win
    IO.race(txProgram("nix", "linux"), retriableTx).void
  }

}
