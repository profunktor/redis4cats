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

package dev.profunktor.redis4cats.tx

import cats.effect.kernel._
import cats.syntax.all._

import dev.profunktor.redis4cats.RedisCommands
import dev.profunktor.redis4cats.effect.TxExecutor

trait RedisTx[F[_]] {
  def exec(fs: List[F[Unit]]): F[Unit]
  def run[A](fs: TxStore[F, String, A] => List[F[Unit]]): F[Map[String, A]]
}

object RedisTx {

  /**
    * Note: a single instance of `RedisCommands` can only handle a transaction at a time.
    *
    * If you wish to run concurrent transactions, each of them needs to run a a dedicated
    * `RedisCommands` instance.
    */
  def make[F[_]: Async, K, V](
      redis: RedisCommands[F, K, V]
  ): Resource[F, RedisTx[F]] =
    TxExecutor.make[F].map { txe =>
      new RedisTx[F] {
        def exec(fs: List[F[Unit]]): F[Unit] =
          run((_: TxStore[F, String, String]) => fs).void

        def run[A](fs: TxStore[F, String, A] => List[F[Unit]]): F[Map[String, A]] =
          TxRunner.run[F, K, V, A](
            acquire = redis.multi,
            release = redis.exec,
            onError = redis.discard,
            t = txe
          )(fs)
      }
    }
}
