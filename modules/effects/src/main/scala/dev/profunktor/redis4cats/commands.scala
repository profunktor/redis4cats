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

package dev.profunktor.redis4cats

import algebra._
import cats.data.NonEmptyList
import cats.effect.kernel.Async
import cats.~>
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.tx.TxStore
import io.lettuce.core.{ GeoArgs, RedisFuture, ZAddArgs, ZAggregateArgs, ZStoreArgs }
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands

import java.time.Instant
import scala.annotation.nowarn
import scala.concurrent.duration.{ Duration, FiniteDuration }

trait RedisCommands[F[_], K, V]
    extends StringCommands[F, K, V]
    with HashCommands[F, K, V]
    with SetCommands[F, K, V]
    with SortedSetCommands[F, K, V]
    with ListCommands[F, K, V]
    with GeoCommands[F, K, V]
    with ConnectionCommands[F, K]
    with ServerCommands[F, K]
    with TransactionalCommands[F, K]
    with PipelineCommands[F]
    with ScriptCommands[F, K, V]
    with KeyCommands[F, K]
    with HyperLogLogCommands[F, K, V]
    with BitCommands[F, K, V]

object RedisCommands {
  implicit class LiftKOps[F[_], K, V](val cmd: RedisCommands[F, K, V]) extends AnyVal {
    def liftK[G[_]: Async: Log]: RedisCommands[G, K, V] =
      cmd.asInstanceOf[BaseRedis[F, K, V]].liftK[G]
  }

  implicit class IMapKOps[F[_], K, V](val cmd: RedisCommands[F, K, V]) extends AnyVal {
    def imapK[G[_]](fg: F ~> G, gf: G ~> F): RedisCommands[G, K, V] =
      new BaseMapKRedisCommands[F, G, K, V](cmd, fg) with RedisCommands[G, K, V] {
        override def transact[A](fs: TxStore[G, String, A] => List[G[Unit]]): G[Map[String, A]] =
          fg(cmd.transact(store => fs(mapTxStore(store, fg)).map(gf.apply)))
        override def transact_(fs: List[G[Unit]]): G[Unit] =
          fg(cmd.transact_(fs.map(gf.apply)))
        override def pipeline[A](fs: TxStore[G, String, A] => List[G[Unit]]): G[Map[String, A]] =
          fg(cmd.pipeline(store => fs(mapTxStore(store, fg)).map(gf.apply)))
        override def pipeline_(fs: List[G[Unit]]): G[Unit] =
          fg(cmd.pipeline_(fs.map(gf.apply)))
      }
  }

  implicit class InstrumentOps[F[_], K, V](val cmd: RedisCommands[F, K, V]) extends AnyVal {
    def instrument(ff: F ~> F, includeTransactions: Boolean): RedisCommands[F, K, V] = {
      if(includeTransactions)
        cmd.imapK(ff, ff)
      else
        new BaseMapKRedisCommands[F, F, K, V](cmd, ff) with RedisCommands[F, K, V] {
          override def transact[A](fs: TxStore[F, String, A] => List[F[Unit]]): F[Map[String, A]] = cmd.transact(fs)
          override def transact_(fs: List[F[Unit]]): F[Unit] = cmd.transact_(fs)
          override def pipeline[A](fs: TxStore[F, String, A] => List[F[Unit]]): F[Map[String, A]] = cmd.pipeline(fs)
          override def pipeline_(fs: List[F[Unit]]): F[Unit] = cmd.pipeline_(fs)
        }
    }
  }

  private def mapTxStore[F[_], G[_], A, B](store: TxStore[F, A, B], fg: F ~> G): TxStore[G, A, B] =
    new TxStore[G, A, B] {
      override def get: G[Map[A, B]]          = fg(store.get)
      override def set(key: A)(v: B): G[Unit] = fg(store.set(key)(v))
    }
}

private class BaseMapKRedisCommands[F[_], G[_], K, V](
    cmd: StringCommands[F, K, V]
      with HashCommands[F, K, V]
      with SetCommands[F, K, V]
      with SortedSetCommands[F, K, V]
      with ListCommands[F, K, V]
      with GeoCommands[F, K, V]
      with ConnectionCommands[F, K]
      with ServerCommands[F, K]
      with PipelineCommands[F]
      with ScriptCommands[F, K, V]
      with KeyCommands[F, K]
      with HyperLogLogCommands[F, K, V]
      with BitCommands[F, K, V]
      with Transaction[F]
      with Watcher[F, K],
    fg: F ~> G
) extends StringCommands[G, K, V]
    with HashCommands[G, K, V]
    with SetCommands[G, K, V]
    with SortedSetCommands[G, K, V]
    with ListCommands[G, K, V]
    with GeoCommands[G, K, V]
    with ConnectionCommands[G, K]
    with ServerCommands[G, K]
    with PipelineCommands[G]
    with ScriptCommands[G, K, V]
    with KeyCommands[G, K]
    with HyperLogLogCommands[G, K, V]
    with BitCommands[G, K, V]
    with Transaction[G]
    with Watcher[G, K] {
  override def setClientName(name: K): G[Boolean] = fg(cmd.setClientName(name))
  override def getClientName(): G[Option[K]]      = fg(cmd.getClientName())
  override def getClientId(): G[Long]             = fg(cmd.getClientId())
  override def zAdd(key: K, args: Option[ZAddArgs], values: effects.ScoreWithValue[V]*): G[Long] =
    fg(cmd.zAdd(key, args, values: _*))
  override def zAddIncr(key: K, args: Option[ZAddArgs], value: effects.ScoreWithValue[V]): G[Double] =
    fg(cmd.zAddIncr(key, args, value))
  override def zIncrBy(key: K, member: V, amount: Double): G[Double] = fg(cmd.zIncrBy(key, member, amount))
  override def zInterStore(destination: K, args: Option[ZStoreArgs], keys: K*): G[Long] =
    fg(cmd.zInterStore(destination, args, keys: _*))
  override def zRem(key: K, values: V*): G[Long]                         = fg(cmd.zRem(key, values: _*))
  override def zRemRangeByLex(key: K, range: effects.ZRange[V]): G[Long] = fg(cmd.zRemRangeByLex(key, range))
  override def zRemRangeByRank(key: K, start: Long, stop: Long): G[Long] = fg(cmd.zRemRangeByRank(key, start, stop))
  override def zRemRangeByScore[T: Numeric](key: K, range: effects.ZRange[T]): G[Long] =
    fg(cmd.zRemRangeByScore[T](key, range))
  override def zUnionStore(destination: K, args: Option[ZStoreArgs], keys: K*): G[Long] =
    fg(cmd.zUnionStore(destination, args, keys: _*))
  override def sPop(key: K): G[Option[V]]                                            = fg(cmd.sPop(key))
  override def sPop(key: K, count: Long): G[Set[V]]                                  = fg(cmd.sPop(key, count))
  override def sRem(key: K, values: V*): G[Long]                                     = fg(cmd.sRem(key, values: _*))
  override def bitCount(key: K): G[Long]                                             = fg(cmd.bitCount(key))
  override def bitCount(key: K, start: Long, end: Long): G[Long]                     = fg(cmd.bitCount(key, start, end))
  override def bitField(key: K, operations: BitCommandOperation*): G[List[Long]]     = fg(cmd.bitField(key, operations: _*))
  override def bitOpAnd(destination: K, sources: K*): G[Unit]                        = fg(cmd.bitOpAnd(destination, sources: _*))
  override def bitOpNot(destination: K, source: K): G[Unit]                          = fg(cmd.bitOpNot(destination, source))
  override def bitOpOr(destination: K, sources: K*): G[Unit]                         = fg(cmd.bitOpOr(destination, sources: _*))
  override def bitOpXor(destination: K, sources: K*): G[Unit]                        = fg(cmd.bitOpXor(destination, sources: _*))
  override def bitPos(key: K, state: Boolean): G[Long]                               = fg(cmd.bitPos(key, state))
  override def bitPos(key: K, state: Boolean, start: Long): G[Long]                  = fg(cmd.bitPos(key, state, start))
  override def bitPos(key: K, state: Boolean, start: Long, end: Long): G[Long]       = fg(cmd.bitPos(key, state, start, end))
  override def getBit(key: K, offset: Long): G[Option[Long]]                         = fg(cmd.getBit(key, offset))
  override def setBit(key: K, offset: Long, value: Int): G[Long]                     = fg(cmd.setBit(key, offset, value))
  override def zCard(key: K): G[Option[Long]]                                        = fg(cmd.zCard(key))
  override def zCount[T: Numeric](key: K, range: effects.ZRange[T]): G[Option[Long]] = fg(cmd.zCount[T](key, range))
  override def zLexCount(key: K, range: effects.ZRange[V]): G[Option[Long]]          = fg(cmd.zLexCount(key, range))
  override def zRange(key: K, start: Long, stop: Long): G[List[V]]                   = fg(cmd.zRange(key, start, stop))
  override def zRangeByLex(key: K, range: effects.ZRange[V], limit: Option[effects.RangeLimit]): G[List[V]] =
    fg(cmd.zRangeByLex(key, range, limit))
  override def zRangeByScore[T: Numeric](
      key: K,
      range: effects.ZRange[T],
      limit: Option[effects.RangeLimit]
  ): G[List[V]] = fg(cmd.zRangeByScore[T](key, range, limit))
  override def zRangeByScoreWithScores[T: Numeric](
      key: K,
      range: effects.ZRange[T],
      limit: Option[effects.RangeLimit]
  ): G[List[effects.ScoreWithValue[V]]] = fg(cmd.zRangeByScoreWithScores[T](key, range, limit))
  override def zRangeWithScores(key: K, start: Long, stop: Long): G[List[effects.ScoreWithValue[V]]] =
    fg(cmd.zRangeWithScores(key, start, stop))
  override def zRank(key: K, value: V): G[Option[Long]]               = fg(cmd.zRank(key, value))
  override def zRevRange(key: K, start: Long, stop: Long): G[List[V]] = fg(cmd.zRevRange(key, start, stop))
  override def zRevRangeByLex(key: K, range: effects.ZRange[V], limit: Option[effects.RangeLimit]): G[List[V]] =
    fg(cmd.zRevRangeByLex(key, range, limit))
  override def zRevRangeByScore[T: Numeric](
      key: K,
      range: effects.ZRange[T],
      limit: Option[effects.RangeLimit]
  ): G[List[V]] = fg(cmd.zRevRangeByScore[T](key, range, limit))
  override def zRevRangeByScoreWithScores[T: Numeric](
      key: K,
      range: effects.ZRange[T],
      limit: Option[effects.RangeLimit]
  ): G[List[effects.ScoreWithValue[V]]] = fg(cmd.zRevRangeByScoreWithScores[T](key, range, limit))
  override def zRevRangeWithScores(key: K, start: Long, stop: Long): G[List[effects.ScoreWithValue[V]]] =
    fg(cmd.zRevRangeWithScores(key, start, stop))
  override def zRevRank(key: K, value: V): G[Option[Long]]                      = fg(cmd.zRevRank(key, value))
  override def zScore(key: K, value: V): G[Option[Double]]                      = fg(cmd.zScore(key, value))
  override def zPopMin(key: K, count: Long): G[List[effects.ScoreWithValue[V]]] = fg(cmd.zPopMin(key, count))
  override def zPopMax(key: K, count: Long): G[List[effects.ScoreWithValue[V]]] = fg(cmd.zPopMax(key, count))
  override def bzPopMax(timeout: Duration, keys: NonEmptyList[K]): G[Option[(K, effects.ScoreWithValue[V])]] =
    fg(cmd.bzPopMax(timeout, keys))
  override def bzPopMin(timeout: Duration, keys: NonEmptyList[K]): G[Option[(K, effects.ScoreWithValue[V])]] =
    fg(cmd.bzPopMin(timeout, keys))
  override def zUnion(args: Option[ZAggregateArgs], keys: K*): G[List[V]] = fg(cmd.zUnion(args, keys: _*))
  override def zUnionWithScores(args: Option[ZAggregateArgs], keys: K*): G[List[effects.ScoreWithValue[V]]] =
    fg(cmd.zUnionWithScores(args, keys: _*))
  override def zInter(args: Option[ZAggregateArgs], keys: K*): G[List[V]] = fg(cmd.zInter(args, keys: _*))
  override def zInterWithScores(args: Option[ZAggregateArgs], keys: K*): G[List[effects.ScoreWithValue[V]]] =
    fg(cmd.zInterWithScores(args, keys: _*))
  override def zDiff(keys: K*): G[List[V]]                                     = fg(cmd.zDiff(keys: _*))
  override def zDiffWithScores(keys: K*): G[List[effects.ScoreWithValue[V]]]   = fg(cmd.zDiffWithScores(keys: _*))
  override def lInsertAfter(key: K, pivot: V, value: V): G[Long]               = fg(cmd.lInsertAfter(key, pivot, value))
  override def lInsertBefore(key: K, pivot: V, value: V): G[Long]              = fg(cmd.lInsertBefore(key, pivot, value))
  override def lRem(key: K, count: Long, value: V): G[Long]                    = fg(cmd.lRem(key, count, value))
  override def lSet(key: K, index: Long, value: V): G[Unit]                    = fg(cmd.lSet(key, index, value))
  override def lTrim(key: K, start: Long, stop: Long): G[Unit]                 = fg(cmd.lTrim(key, start, stop))
  override def lIndex(key: K, index: Long): G[Option[V]]                       = fg(cmd.lIndex(key, index))
  override def lLen(key: K): G[Option[Long]]                                   = fg(cmd.lLen(key))
  override def lRange(key: K, start: Long, stop: Long): G[List[V]]             = fg(cmd.lRange(key, start, stop))
  override def keys(key: K): G[List[K]]                                        = fg(cmd.keys(key))
  override def flushAll: G[Unit]                                               = fg(cmd.flushAll)
  override def watch(keys: K*): G[Unit]                                        = fg(cmd.watch(keys: _*))
  override def unwatch: G[Unit]                                                = fg(cmd.unwatch)
  override def enableAutoFlush: G[Unit]                                        = fg(cmd.enableAutoFlush)
  override def disableAutoFlush: G[Unit]                                       = fg(cmd.disableAutoFlush)
  override def flushCommands: G[Unit]                                          = fg(cmd.flushCommands)
  override def sIsMember(key: K, value: V): G[Boolean]                         = fg(cmd.sIsMember(key, value))
  override def sMisMember(key: K, values: V*): G[List[Boolean]]                = fg(cmd.sMisMember(key, values: _*))
  override def multi: G[Unit]                                                  = fg(cmd.multi)
  override def exec: G[Unit]                                                   = fg(cmd.exec)
  override def discard: G[Unit]                                                = fg(cmd.discard)
  override def sCard(key: K): G[Long]                                          = fg(cmd.sCard(key))
  override def sDiff(keys: K*): G[Set[V]]                                      = fg(cmd.sDiff(keys: _*))
  override def sInter(keys: K*): G[Set[V]]                                     = fg(cmd.sInter(keys: _*))
  override def sMembers(key: K): G[Set[V]]                                     = fg(cmd.sMembers(key))
  override def sRandMember(key: K): G[Option[V]]                               = fg(cmd.sRandMember(key))
  override def sRandMember(key: K, count: Long): G[List[V]]                    = fg(cmd.sRandMember(key, count))
  override def sUnion(keys: K*): G[Set[V]]                                     = fg(cmd.sUnion(keys: _*))
  override def sUnionStore(destination: K, keys: K*): G[Unit]                  = fg(cmd.sUnionStore(destination, keys: _*))
  override def hGet(key: K, field: K): G[Option[V]]                            = fg(cmd.hGet(key, field))
  override def hGetAll(key: K): G[Map[K, V]]                                   = fg(cmd.hGetAll(key))
  override def hmGet(key: K, fields: K*): G[Map[K, V]]                         = fg(cmd.hmGet(key, fields: _*))
  override def hKeys(key: K): G[List[K]]                                       = fg(cmd.hKeys(key))
  override def hVals(key: K): G[List[V]]                                       = fg(cmd.hVals(key))
  override def hStrLen(key: K, field: K): G[Option[Long]]                      = fg(cmd.hStrLen(key, field))
  override def hLen(key: K): G[Option[Long]]                                   = fg(cmd.hLen(key))
  override def hSet(key: K, field: K, value: V): G[Boolean]                    = fg(cmd.hSet(key, field, value))
  override def hSet(key: K, fieldValues: Map[K, V]): G[Long]                   = fg(cmd.hSet(key, fieldValues))
  override def hSetNx(key: K, field: K, value: V): G[Boolean]                  = fg(cmd.hSetNx(key, field, value))
  @nowarn override def hmSet(key: K, fieldValues: Map[K, V]): G[Unit]          = fg(cmd.hmSet(key, fieldValues))
  override def del(key: K*): G[Long]                                           = fg(cmd.del(key: _*))
  override def exists(key: K*): G[Boolean]                                     = fg(cmd.exists(key: _*))
  override def expire(key: K, expiresIn: FiniteDuration): G[Boolean]           = fg(cmd.expire(key, expiresIn))
  override def expireAt(key: K, at: Instant): G[Boolean]                       = fg(cmd.expireAt(key, at))
  override def objectIdletime(key: K): G[Option[FiniteDuration]]               = fg(cmd.objectIdletime(key))
  override def ttl(key: K): G[Option[FiniteDuration]]                          = fg(cmd.ttl(key))
  override def pttl(key: K): G[Option[FiniteDuration]]                         = fg(cmd.pttl(key))
  override def scan: G[data.KeyScanCursor[K]]                                  = fg(cmd.scan)
  @nowarn override def scan(cursor: Long): G[data.KeyScanCursor[K]]            = fg(cmd.scan(cursor))
  override def scan(previous: data.KeyScanCursor[K]): G[data.KeyScanCursor[K]] = fg(cmd.scan(previous))
  override def scan(scanArgs: effects.ScanArgs): G[data.KeyScanCursor[K]]      = fg(cmd.scan(scanArgs))
  @nowarn override def scan(cursor: Long, scanArgs: effects.ScanArgs): G[data.KeyScanCursor[K]] =
    fg(cmd.scan(cursor, scanArgs))
  override def scan(previous: data.KeyScanCursor[K], scanArgs: effects.ScanArgs): G[data.KeyScanCursor[K]] =
    fg(cmd.scan(previous, scanArgs))
  override def hIncrBy(key: K, field: K, amount: Long): G[Long]                       = fg(cmd.hIncrBy(key, field, amount))
  override def hIncrByFloat(key: K, field: K, amount: Double): G[Double]              = fg(cmd.hIncrByFloat(key, field, amount))
  override def eval(script: String, output: effects.ScriptOutputType[V]): G[output.R] = fg(cmd.eval(script, output))
  override def eval(script: String, output: effects.ScriptOutputType[V], keys: List[K]): G[output.R] =
    fg(cmd.eval(script, output, keys))
  override def eval(script: String, output: effects.ScriptOutputType[V], keys: List[K], values: List[V]): G[output.R] =
    fg(cmd.eval(script, output, keys, values))
  override def evalSha(digest: String, output: effects.ScriptOutputType[V]): G[output.R] =
    fg(cmd.evalSha(digest, output))
  override def evalSha(digest: String, output: effects.ScriptOutputType[V], keys: List[K]): G[output.R] =
    fg(cmd.evalSha(digest, output, keys))
  override def evalSha(
      digest: String,
      output: effects.ScriptOutputType[V],
      keys: List[K],
      values: List[V]
  ): G[output.R]                                                                     = fg(cmd.evalSha(digest, output, keys, values))
  override def scriptLoad(script: String): G[String]                                 = fg(cmd.scriptLoad(script))
  override def scriptLoad(script: Array[Byte]): G[String]                            = fg(cmd.scriptLoad(script))
  override def scriptExists(digests: String*): G[List[Boolean]]                      = fg(cmd.scriptExists(digests: _*))
  override def scriptFlush: G[Unit]                                                  = fg(cmd.scriptFlush)
  override def digest(script: String): G[String]                                     = fg(cmd.digest(script))
  override def info: G[Map[String, String]]                                          = fg(cmd.info)
  override def info(section: String): G[Map[String, String]]                         = fg(cmd.info(section))
  override def dbsize: G[Long]                                                       = fg(cmd.dbsize)
  override def lastSave: G[Instant]                                                  = fg(cmd.lastSave)
  override def slowLogLen: G[Long]                                                   = fg(cmd.slowLogLen)
  override def pfAdd(key: K, values: V*): G[Long]                                    = fg(cmd.pfAdd(key, values: _*))
  override def pfCount(key: K): G[Long]                                              = fg(cmd.pfCount(key))
  override def pfMerge(outputKey: K, inputKeys: K*): G[Unit]                         = fg(cmd.pfMerge(outputKey, inputKeys: _*))
  override def unsafe[A](f: RedisClusterAsyncCommands[K, V] => RedisFuture[A]): G[A] = fg(cmd.unsafe(f))
  override def unsafeSync[A](f: RedisClusterAsyncCommands[K, V] => A): G[A]          = fg(cmd.unsafeSync(f))
  override def geoRadius(
      key: K,
      geoRadius: effects.GeoRadius,
      unit: GeoArgs.Unit,
      args: GeoArgs
  ): G[List[effects.GeoRadiusResult[V]]] = fg(cmd.geoRadius(key, geoRadius, unit, args))
  override def geoRadiusByMember(key: K, value: V, dist: effects.Distance, unit: GeoArgs.Unit): G[Set[V]] =
    fg(cmd.geoRadiusByMember(key, value, dist, unit))
  override def geoRadiusByMember(
      key: K,
      value: V,
      dist: effects.Distance,
      unit: GeoArgs.Unit,
      args: GeoArgs
  ): G[List[effects.GeoRadiusResult[V]]]                                   = fg(cmd.geoRadiusByMember(key, value, dist, unit, args))
  override def geoAdd(key: K, geoValues: effects.GeoLocation[V]*): G[Unit] = fg(cmd.geoAdd(key: K, geoValues: _*))
  override def geoRadius(
      key: K,
      geoRadius: effects.GeoRadius,
      unit: GeoArgs.Unit,
      storage: effects.GeoRadiusKeyStorage[K]
  ): G[Unit] = fg(cmd.geoRadius(key, geoRadius, unit, storage))
  override def geoRadius(
      key: K,
      geoRadius: effects.GeoRadius,
      unit: GeoArgs.Unit,
      storage: effects.GeoRadiusDistStorage[K]
  ): G[Unit] = fg(cmd.geoRadius(key, geoRadius, unit, storage))
  override def geoRadiusByMember(
      key: K,
      value: V,
      dist: effects.Distance,
      unit: GeoArgs.Unit,
      storage: effects.GeoRadiusKeyStorage[K]
  ): G[Unit] = fg(cmd.geoRadiusByMember(key, value, dist, unit, storage))
  override def geoRadiusByMember(
      key: K,
      value: V,
      dist: effects.Distance,
      unit: GeoArgs.Unit,
      storage: effects.GeoRadiusDistStorage[K]
  ): G[Unit]                                                                  = fg(cmd.geoRadiusByMember(key, value, dist, unit, storage))
  override def geoDist(key: K, from: V, to: V, unit: GeoArgs.Unit): G[Double] = fg(cmd.geoDist(key, from, to, unit))
  override def geoHash(key: K, values: V*): G[List[Option[String]]]           = fg(cmd.geoHash(key, values: _*))
  override def geoPos(key: K, values: V*): G[List[effects.GeoCoordinate]]     = fg(cmd.geoPos(key, values: _*))
  override def geoRadius(key: K, geoRadius: effects.GeoRadius, unit: GeoArgs.Unit): G[Set[V]] =
    fg(cmd.geoRadius(key, geoRadius, unit))
  override def sAdd(key: K, values: V*): G[Long]                                  = fg(cmd.sAdd(key, values: _*))
  override def sDiffStore(destination: K, keys: K*): G[Long]                      = fg(cmd.sDiffStore(destination, keys: _*))
  override def sInterStore(destination: K, keys: K*): G[Long]                     = fg(cmd.sInterStore(destination, keys: _*))
  override def sMove(source: K, destination: K, value: V): G[Boolean]             = fg(cmd.sMove(source, destination, value))
  override def decr(key: K): G[Long]                                              = fg(cmd.decr(key))
  override def decrBy(key: K, amount: Long): G[Long]                              = fg(cmd.decrBy(key, amount))
  override def incr(key: K): G[Long]                                              = fg(cmd.incr(key))
  override def incrBy(key: K, amount: Long): G[Long]                              = fg(cmd.incrBy(key, amount))
  override def incrByFloat(key: K, amount: Double): G[Double]                     = fg(cmd.incrByFloat(key, amount))
  override def lPop(key: K): G[Option[V]]                                         = fg(cmd.lPop(key))
  override def lPush(key: K, values: V*): G[Long]                                 = fg(cmd.lPush(key, values: _*))
  override def lPushX(key: K, values: V*): G[Long]                                = fg(cmd.lPushX(key, values: _*))
  override def rPop(key: K): G[Option[V]]                                         = fg(cmd.rPop(key))
  override def rPopLPush(source: K, destination: K): G[Option[V]]                 = fg(cmd.rPopLPush(source, destination))
  override def rPush(key: K, values: V*): G[Long]                                 = fg(cmd.rPush(key, values: _*))
  override def rPushX(key: K, values: V*): G[Long]                                = fg(cmd.rPushX(key, values: _*))
  override def append(key: K, value: V): G[Unit]                                  = fg(cmd.append(key, value))
  override def getSet(key: K, value: V): G[Option[V]]                             = fg(cmd.getSet(key, value))
  override def set(key: K, value: V): G[Unit]                                     = fg(cmd.set(key, value))
  override def set(key: K, value: V, setArgs: effects.SetArgs): G[Boolean]        = fg(cmd.set(key, value, setArgs))
  override def setNx(key: K, value: V): G[Boolean]                                = fg(cmd.setNx(key, value))
  override def setEx(key: K, value: V, expiresIn: FiniteDuration): G[Unit]        = fg(cmd.setEx(key, value, expiresIn))
  override def setRange(key: K, value: V, offset: Long): G[Unit]                  = fg(cmd.setRange(key, value, offset))
  override def ping: G[String]                                                    = fg(cmd.ping)
  override def select(index: Int): G[Unit]                                        = fg(cmd.select(index))
  override def mGet(keys: Set[K]): G[Map[K, V]]                                   = fg(cmd.mGet(keys))
  override def mSet(keyValues: Map[K, V]): G[Unit]                                = fg(cmd.mSet(keyValues))
  override def mSetNx(keyValues: Map[K, V]): G[Boolean]                           = fg(cmd.mSetNx(keyValues))
  override def get(key: K): G[Option[V]]                                          = fg(cmd.get(key))
  override def getEx(key: K, getExArg: effects.GetExArg): G[Option[V]]            = fg(cmd.getEx(key, getExArg))
  override def getRange(key: K, start: Long, end: Long): G[Option[V]]             = fg(cmd.getRange(key, start, end))
  override def strLen(key: K): G[Option[Long]]                                    = fg(cmd.strLen(key))
  override def auth(password: CharSequence): G[Boolean]                           = fg(cmd.auth(password))
  override def auth(username: String, password: CharSequence): G[Boolean]         = fg(cmd.auth(username, password))
  override def hDel(key: K, fields: K*): G[Long]                                  = fg(cmd.hDel(key: K, fields: _*))
  override def hExists(key: K, field: K): G[Boolean]                              = fg(cmd.hExists(key, field))
  override def blPop(timeout: Duration, keys: NonEmptyList[K]): G[Option[(K, V)]] = fg(cmd.blPop(timeout, keys))
  override def brPop(timeout: Duration, keys: NonEmptyList[K]): G[Option[(K, V)]] = fg(cmd.brPop(timeout, keys))
  override def brPopLPush(timeout: Duration, source: K, destination: K): G[Option[V]] =
    fg(cmd.brPopLPush(timeout, source, destination))
}
