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

package dev.profunktor.redis4cats.algebra

import dev.profunktor.redis4cats.tx.TxStore

trait TransactionalCommands[F[_], K] extends Transaction[F] with Watcher[F, K] with HighLevelTx[F] with Pipelining[F]

trait Transaction[F[_]] {
  def multi: F[Unit]
  def exec: F[Unit]
  def discard: F[Unit]
}

trait Watcher[F[_], K] {
  def watch(keys: K*): F[Unit]
  def unwatch: F[Unit]
}

trait HighLevelTx[F[_]] {
  def transact[A](fs: TxStore[F, String, A] => List[F[Unit]]): F[Map[String, A]]
  def transact_(fs: List[F[Unit]]): F[Unit]
}

trait Pipelining[F[_]] {
  def pipeline[A](fs: TxStore[F, String, A] => List[F[Unit]]): F[Map[String, A]]
  def pipeline_(fs: List[F[Unit]]): F[Unit]
}
