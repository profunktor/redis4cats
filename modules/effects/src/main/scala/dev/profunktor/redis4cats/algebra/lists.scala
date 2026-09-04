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

import cats.data.NonEmptyList
import dev.profunktor.redis4cats.effects.{ LMoveCount, LMoveSide, LPosArgs }

import scala.concurrent.duration.Duration

trait ListCommands[F[_], K, V]
    extends ListBlocking[F, K, V]
    with ListGetter[F, K, V]
    with ListSetter[F, K, V]
    with ListPushPop[F, K, V]

trait ListBlocking[F[_], K, V] {
  def blPop(timeout: Duration, keys: NonEmptyList[K]): F[Option[(K, V)]]
  def brPop(timeout: Duration, keys: NonEmptyList[K]): F[Option[(K, V)]]
  def brPopLPush(timeout: Duration, source: K, destination: K): F[Option[V]]
  def blMove(
      timeout: Duration,
      source: K,
      destination: K,
      sourceSide: LMoveSide,
      destinationSide: LMoveSide
  ): F[Option[V]]
  def blMoveMany(
      timeout: Duration,
      source: K,
      destination: K,
      sourceSide: LMoveSide,
      destinationSide: LMoveSide
  ): F[List[V]]
  def blMoveMany(
      timeout: Duration,
      source: K,
      destination: K,
      sourceSide: LMoveSide,
      destinationSide: LMoveSide,
      count: LMoveCount
  ): F[List[V]]
  def blmPop(timeout: Duration, keys: NonEmptyList[K], side: LMoveSide): F[Option[(K, List[V])]]
  def blmPop(timeout: Duration, keys: NonEmptyList[K], side: LMoveSide, count: Long): F[Option[(K, List[V])]]
}

trait ListGetter[F[_], K, V] {
  def lIndex(key: K, index: Long): F[Option[V]]
  def lLen(key: K): F[Long]
  def lRange(key: K, start: Long, stop: Long): F[List[V]]
  def lPos(key: K, value: V): F[Option[Long]]
  def lPos(key: K, value: V, args: LPosArgs): F[Option[Long]]

  def lPos(key: K, value: V, count: Int): F[List[Long]]
  def lPos(key: K, value: V, count: Int, args: LPosArgs): F[List[Long]]
}

trait ListSetter[F[_], K, V] {
  def lInsertAfter(key: K, pivot: V, value: V): F[Long]
  def lInsertBefore(key: K, pivot: V, value: V): F[Long]
  def lRem(key: K, count: Long, value: V): F[Long]
  def lSet(key: K, index: Long, value: V): F[Unit]
  def lTrim(key: K, start: Long, stop: Long): F[Unit]
}

trait ListPushPop[F[_], K, V] {
  def lPop(key: K): F[Option[V]]
  def lPop(key: K, count: Long): F[List[V]]
  def lPush(key: K, values: V*): F[Long]
  def lPushX(key: K, values: V*): F[Long]
  def rPop(key: K): F[Option[V]]
  def rPop(key: K, count: Long): F[List[V]]
  def rPopLPush(source: K, destination: K): F[Option[V]]
  def lMove(source: K, destination: K, sourceSide: LMoveSide, destinationSide: LMoveSide): F[Option[V]]
  def lMoveMany(source: K, destination: K, sourceSide: LMoveSide, destinationSide: LMoveSide): F[List[V]]
  def lMoveMany(
      source: K,
      destination: K,
      sourceSide: LMoveSide,
      destinationSide: LMoveSide,
      count: LMoveCount
  ): F[List[V]]
  def lmPop(keys: NonEmptyList[K], side: LMoveSide): F[Option[(K, List[V])]]
  def lmPop(keys: NonEmptyList[K], side: LMoveSide, count: Long): F[Option[(K, List[V])]]
  def rPush(key: K, values: V*): F[Long]
  def rPushX(key: K, values: V*): F[Long]
}
