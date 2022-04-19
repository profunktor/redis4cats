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
import cats.effect.kernel.syntax.all._
import cats.syntax.all._

import dev.profunktor.redis4cats.RedisCommands
import dev.profunktor.redis4cats.effect.TxExecutor

trait RedisTx[F[_]] {
  def run[A](fs: RedisTx.Store[F, String, A] => List[F[Unit]]): F[Map[String, A]]
  def run_(fs: List[F[Unit]]): F[Unit]
}

object RedisTx {

  /**
    * Provides a way to store transactional results for later retrieval.
    */
  trait Store[F[_], K, V] {
    def get: F[Map[K, V]]
    def set(key: K)(v: V): F[Unit]
  }

  object Store {
    private[redis4cats] def make[F[_]: Async, K, V]: F[Store[F, K, V]] =
      Ref.of[F, Map[K, V]](Map.empty).map { ref =>
        new Store[F, K, V] {
          def get: F[Map[K, V]]          = ref.get
          def set(key: K)(v: V): F[Unit] = ref.update(_.updated(key, v))
        }
      }
  }

  /**
    * Note: a single instance of `RedisCommands` can only handle a transaction at a time.
    *
    * If you wish to run concurrent transactions, each of them needs to run a a dedicated
    * `RedisCommands` instance.
    */
  def make[F[_]: Async, K, V](
      redis: RedisCommands[F, K, V]
  ): Resource[F, RedisTx[F]] =
    TxExecutor.make[F].map { t =>
      new RedisTx[F] {
        def run_(fs: List[F[Unit]]): F[Unit] = run((_: Store[F, String, String]) => fs).void

        def run[A](fs: Store[F, String, A] => List[F[Unit]]): F[Map[String, A]] =
          Store.make[F, String, A].flatMap { store =>
            (Deferred[F, Unit], Ref.of[F, List[Fiber[F, Throwable, Unit]]](List.empty)).tupled.flatMap {
              case (gate, fbs) =>
                t.eval(redis.multi)
                  .bracketCase { _ =>
                    fs(store)
                      .traverse_(f => t.start(f).flatMap(fb => fbs.update(_ :+ fb)))
                      .guarantee(gate.complete(()).void)
                  } {
                    case (_, Outcome.Succeeded(_)) =>
                      gate.get *> t.eval(redis.exec).guarantee(fbs.get.flatMap(_.traverse_(_.join)))
                    case (_, _) =>
                      t.eval(redis.discard).guarantee(fbs.get.flatMap(_.traverse_(_.cancel)))
                  }
            } *> store.get
          }
      }
    }
}
