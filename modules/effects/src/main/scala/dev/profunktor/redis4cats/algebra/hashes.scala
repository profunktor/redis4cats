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

package dev.profunktor.redis4cats.algebra

trait HashCommands[F[_], K, V] extends HashGetter[F, K, V] with HashSetter[F, K, V] with HashIncrement[F, K, V] {
  def hDel(key: K, fields: K*): F[Long]
  def hExists(key: K, field: K): F[Boolean]
}

trait HashGetter[F[_], K, V] {
  def hGet(key: K, field: K): F[Option[V]]
  def hGetAll(key: K): F[Map[K, V]]
  def hmGet(key: K, fields: K*): F[Map[K, V]]
  def hKeys(key: K): F[List[K]]
  def hVals(key: K): F[List[V]]
  def hStrLen(key: K, field: K): F[Option[Long]]
  def hLen(key: K): F[Option[Long]]
}

trait HashSetter[F[_], K, V] {
  def hSet(key: K, field: K, value: V): F[Boolean]
  def hSetNx(key: K, field: K, value: V): F[Boolean]
  def hmSet(key: K, fieldValues: Map[K, V]): F[Unit]
}

trait HashIncrement[F[_], K, V] {
  def hIncrBy(key: K, field: K, amount: Long)(implicit N: Numeric[V]): F[Long]
  def hIncrByFloat(key: K, field: K, amount: Double)(implicit N: Numeric[V]): F[Double]
}
