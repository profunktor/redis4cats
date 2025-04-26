/*
 * Copyright 2018-2025 ProfunKtor
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

import cats.effect.kernel.{ Async, Ref }
import cats.syntax.functor._

/** Provides a way to store transactional results for later retrieval.
  */
trait TxStore[F[_], K, V] {
  def get: F[Map[K, V]]
  def set(key: K)(v: V): F[Unit]
}

object TxStore {
  private[redis4cats] def make[F[_]: Async, K, V]: F[TxStore[F, K, V]] =
    Ref.of[F, Map[K, V]](Map.empty).map { ref =>
      new TxStore[F, K, V] {
        def get: F[Map[K, V]]          = ref.get
        def set(key: K)(v: V): F[Unit] = ref.update(_.updated(key, v))
      }
    }
}
