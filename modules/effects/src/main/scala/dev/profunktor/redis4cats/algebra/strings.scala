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

import dev.profunktor.redis4cats.effects.SetArgs

import scala.concurrent.duration.FiniteDuration

trait StringCommands[F[_], K, V]
    extends Getter[F, K, V]
    with Setter[F, K, V]
    with MultiKey[F, K, V]
    with Decrement[F, K, V]
    with Increment[F, K, V]
    with Bits[F, K, V]

trait Getter[F[_], K, V] {
  def get(key: K): F[Option[V]]
  def getBit(key: K, offset: Long): F[Option[Long]]
  def getRange(key: K, start: Long, end: Long): F[Option[V]]
  def strLen(key: K): F[Option[Long]]
}

trait Setter[F[_], K, V] {
  def append(key: K, value: V): F[Unit]
  def getSet(key: K, value: V): F[Option[V]]
  def set(key: K, value: V): F[Unit]
  def set(key: K, value: V, setArgs: SetArgs): F[Boolean]
  def setNx(key: K, value: V): F[Boolean]
  def setEx(key: K, value: V, expiresIn: FiniteDuration): F[Unit]
  def setRange(key: K, value: V, offset: Long): F[Unit]
}

trait MultiKey[F[_], K, V] {
  def mGet(keys: Set[K]): F[Map[K, V]]
  def mSet(keyValues: Map[K, V]): F[Unit]
  def mSetNx(keyValues: Map[K, V]): F[Boolean]
}

trait Decrement[F[_], K, V] {
  def decr(key: K)(implicit N: Numeric[V]): F[Long]
  def decrBy(key: K, amount: Long)(implicit N: Numeric[V]): F[Long]
}

trait Increment[F[_], K, V] {
  def incr(key: K)(implicit N: Numeric[V]): F[Long]
  def incrBy(key: K, amount: Long)(implicit N: Numeric[V]): F[Long]
  def incrByFloat(key: K, amount: Double)(implicit N: Numeric[V]): F[Double]
}

trait Bits[F[_], K, V] {
  def bitCount(key: K): F[Long]
  def bitCount(key: K, start: Long, end: Long): F[Long]
  def bitPos(key: K, state: Boolean): F[Long]
  def bitPos(key: K, state: Boolean, start: Long): F[Long]
  def bitPos(key: K, state: Boolean, start: Long, end: Long): F[Long]
  def bitOpAnd(destination: K, sources: K*): F[Unit]
  def bitOpNot(destination: K, source: K): F[Unit]
  def bitOpOr(destination: K, sources: K*): F[Unit]
  def bitOpXor(destination: K, sources: K*): F[Unit]
}
