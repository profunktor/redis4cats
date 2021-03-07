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

import cats.data.NonEmptyList
import dev.profunktor.redis4cats.effects.{ RangeLimit, ScoreWithValue, ZRange }
import io.lettuce.core.{ ZAddArgs, ZStoreArgs }

import scala.concurrent.duration.Duration

trait SortedSetCommands[F[_], K, V] extends SortedSetGetter[F, K, V] with SortedSetSetter[F, K, V]

trait SortedSetGetter[F[_], K, V] {
  def zCard(key: K): F[Option[Long]]
  def zCount[T: Numeric](key: K, range: ZRange[T]): F[Option[Long]]
  def zLexCount(key: K, range: ZRange[V]): F[Option[Long]]
  def zRange(key: K, start: Long, stop: Long): F[List[V]]
  def zRangeByLex(key: K, range: ZRange[V], limit: Option[RangeLimit]): F[List[V]]
  def zRangeByScore[T: Numeric](key: K, range: ZRange[T], limit: Option[RangeLimit]): F[List[V]]
  def zRangeByScoreWithScores[T: Numeric](
      key: K,
      range: ZRange[T],
      limit: Option[RangeLimit]
  ): F[List[ScoreWithValue[V]]]
  def zRangeWithScores(key: K, start: Long, stop: Long): F[List[ScoreWithValue[V]]]
  def zRank(key: K, value: V): F[Option[Long]]
  def zRevRange(key: K, start: Long, stop: Long): F[List[V]]
  def zRevRangeByLex(key: K, range: ZRange[V], limit: Option[RangeLimit]): F[List[V]]
  def zRevRangeByScore[T: Numeric](key: K, range: ZRange[T], limit: Option[RangeLimit]): F[List[V]]
  def zRevRangeByScoreWithScores[T: Numeric](
      key: K,
      range: ZRange[T],
      limit: Option[RangeLimit]
  ): F[List[ScoreWithValue[V]]]
  def zRevRangeWithScores(key: K, start: Long, stop: Long): F[List[ScoreWithValue[V]]]
  def zRevRank(key: K, value: V): F[Option[Long]]
  def zScore(key: K, value: V): F[Option[Double]]
  def zPopMin(key: K, count: Long): F[List[ScoreWithValue[V]]]
  def zPopMax(key: K, count: Long): F[List[ScoreWithValue[V]]]
  def bzPopMax(timeout: Duration, keys: NonEmptyList[K]): F[Option[(K, ScoreWithValue[V])]]
  def bzPopMin(timeout: Duration, keys: NonEmptyList[K]): F[Option[(K, ScoreWithValue[V])]]
}

trait SortedSetSetter[F[_], K, V] {
  def zAdd(key: K, args: Option[ZAddArgs], values: ScoreWithValue[V]*): F[Long]
  def zAddIncr(key: K, args: Option[ZAddArgs], value: ScoreWithValue[V]): F[Double]
  def zIncrBy(key: K, member: V, amount: Double): F[Double]
  def zInterStore(destination: K, args: Option[ZStoreArgs], keys: K*): F[Long]
  def zRem(key: K, values: V*): F[Long]
  def zRemRangeByLex(key: K, range: ZRange[V]): F[Long]
  def zRemRangeByRank(key: K, start: Long, stop: Long): F[Long]
  def zRemRangeByScore[T: Numeric](key: K, range: ZRange[T]): F[Long]
  def zUnionStore(destination: K, args: Option[ZStoreArgs], keys: K*): F[Long]
}
