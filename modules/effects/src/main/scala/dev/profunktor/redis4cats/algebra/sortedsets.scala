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
import dev.profunktor.redis4cats.effects.{ RangeLimit, ScoreWithValue, ZRange }
import io.lettuce.core.{ ZAddArgs, ZAggregateArgs, ZStoreArgs }

import scala.concurrent.duration.Duration

trait SortedSetCommands[F[_], K, V] extends SortedSetGetter[F, K, V] with SortedSetSetter[F, K, V]

trait SortedSetGetter[F[_], K, V] {
  def zCard(key: K): F[Long]
  def zCount[T: Numeric](key: K, range: ZRange[T]): F[Long]
  def zLexCount(key: K, range: ZRange[V]): F[Long]
  def zMScore(key: K, values: V*): F[List[Option[Double]]]
  def zRandMember(key: K): F[Option[V]]
  def zRandMember(key: K, count: Long): F[List[V]]
  def zRandMemberWithScores(key: K): F[Option[ScoreWithValue[V]]]
  def zRandMemberWithScores(key: K, count: Long): F[List[ScoreWithValue[V]]]
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

  /** Pops up to `count` members with the lowest scores from the first non-empty of `keys`. `count` is `Int`, not `Long`
    * like its blocking sibling [[bzmPopMin]] - Lettuce's non-blocking `ZMPOP` only exposes an `Int`-count overload.
    */
  def zmPopMin(keys: NonEmptyList[K], count: Int): F[Option[(K, List[ScoreWithValue[V]])]]

  /** Pops up to `count` members with the highest scores from the first non-empty of `keys`. See [[zmPopMin]] for why
    * `count` is `Int` here but `Long` on the blocking [[bzmPopMax]].
    */
  def zmPopMax(keys: NonEmptyList[K], count: Int): F[Option[(K, List[ScoreWithValue[V]])]]
  def bzmPopMin(timeout: Duration, keys: NonEmptyList[K], count: Long): F[Option[(K, List[ScoreWithValue[V]])]]
  def bzmPopMax(timeout: Duration, keys: NonEmptyList[K], count: Long): F[Option[(K, List[ScoreWithValue[V]])]]
  def zUnion(args: Option[ZAggregateArgs], keys: K*): F[List[V]]
  def zUnionWithScores(args: Option[ZAggregateArgs], keys: K*): F[List[ScoreWithValue[V]]]
  def zInter(args: Option[ZAggregateArgs], keys: K*): F[List[V]]
  def zInterWithScores(args: Option[ZAggregateArgs], keys: K*): F[List[ScoreWithValue[V]]]

  /** Cardinality of the intersection of `keys`, without materializing it. */
  def zInterCard(keys: K*): F[Long]

  /** As [[zInterCard]], but stops counting once `limit` is reached (0 means unlimited). */
  def zInterCard(limit: Long, keys: K*): F[Long]
  def zDiff(keys: K*): F[List[V]]
  def zDiffWithScores(keys: K*): F[List[ScoreWithValue[V]]]
}

trait SortedSetSetter[F[_], K, V] {
  def zAdd(key: K, args: Option[ZAddArgs], values: ScoreWithValue[V]*): F[Long]
  def zAddIncr(key: K, args: Option[ZAddArgs], value: ScoreWithValue[V]): F[Double]
  def zIncrBy(key: K, member: V, amount: Double): F[Double]
  def zInterStore(destination: K, args: Option[ZStoreArgs], keys: K*): F[Long]
  def zRem(key: K, value: V, values: V*): F[Long]
  def zRemRangeByLex(key: K, range: ZRange[V]): F[Long]
  def zRemRangeByRank(key: K, start: Long, stop: Long): F[Long]
  def zRemRangeByScore[T: Numeric](key: K, range: ZRange[T]): F[Long]
  def zUnionStore(destination: K, args: Option[ZStoreArgs], keys: K*): F[Long]

  /** Stores the by-rank range `[start, stop]` of `key` into `destination`, returning the count stored. */
  def zRangeStore(destination: K, key: K, start: Long, stop: Long): F[Long]
  def zRangeStoreByScore[T: Numeric](destination: K, key: K, range: ZRange[T], limit: Option[RangeLimit]): F[Long]
  def zRangeStoreByLex(destination: K, key: K, range: ZRange[V], limit: Option[RangeLimit]): F[Long]
  def zRevRangeStore(destination: K, key: K, start: Long, stop: Long): F[Long]
  def zRevRangeStoreByScore[T: Numeric](destination: K, key: K, range: ZRange[T], limit: Option[RangeLimit]): F[Long]
  def zRevRangeStoreByLex(destination: K, key: K, range: ZRange[V], limit: Option[RangeLimit]): F[Long]
}
