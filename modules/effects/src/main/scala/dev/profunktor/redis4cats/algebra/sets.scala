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

trait SetCommands[F[_], K, V] extends SetGetter[F, K, V] with SetSetter[F, K, V] with SetDeletion[F, K, V] {
  def sIsMember(key: K, value: V): F[Boolean]
}

trait SetGetter[F[_], K, V] {
  def sCard(key: K): F[Long]
  def sDiff(keys: K*): F[Set[V]]
  def sInter(keys: K*): F[Set[V]]
  def sMembers(key: K): F[Set[V]]
  def sRandMember(key: K): F[Option[V]]
  def sRandMember(key: K, count: Long): F[List[V]]
  def sUnion(keys: K*): F[Set[V]]
  def sUnionStore(destination: K, keys: K*): F[Unit]
}

trait SetSetter[F[_], K, V] {
  def sAdd(key: K, values: V*): F[Long]
  def sDiffStore(destination: K, keys: K*): F[Long]
  def sInterStore(destination: K, keys: K*): F[Long]
  def sMove(source: K, destination: K, value: V): F[Boolean]
}

trait SetDeletion[F[_], K, V] {
  def sPop(key: K): F[Option[V]]
  def sPop(key: K, count: Long): F[Set[V]]
  def sRem(key: K, values: V*): F[Unit]
}
