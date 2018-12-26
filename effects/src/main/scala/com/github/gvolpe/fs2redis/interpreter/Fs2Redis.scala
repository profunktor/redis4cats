/*
 * Copyright 2018-2019 Fs2 Redis
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

package com.github.gvolpe.fs2redis.interpreter
import java.util.concurrent.TimeUnit

import cats.implicits._
import cats.effect.{ Concurrent, Resource, Sync }
import com.github.gvolpe.fs2redis.algebra._
import com.github.gvolpe.fs2redis.connection._
import com.github.gvolpe.fs2redis.domain._
import com.github.gvolpe.fs2redis.effect.{ JRFuture, Log }
import com.github.gvolpe.fs2redis.effects._
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis.RedisCommands
import io.lettuce.core._

import scala.concurrent.duration.FiniteDuration

object Fs2Redis {

  sealed trait RedisCommands[F[_], K, V]
      extends StringCommands[F, K, V]
      with HashCommands[F, K, V]
      with SetCommands[F, K, V]
      with SortedSetCommands[F, K, V]
      with ListCommands[F, K, V]
      with GeoCommands[F, K, V]
      with ConnectionCommands[F]
      with ServerCommands[F]

  private[fs2redis] def acquireAndRelease[F[_]: Concurrent: Log, K, V](
      client: Fs2RedisClient,
      codec: Fs2RedisCodec[K, V],
      uri: RedisURI
  ): (F[Fs2Redis[F, K, V]], Fs2Redis[F, K, V] => F[Unit]) = {
    val acquire = JRFuture
      .fromConnectionFuture {
        Sync[F].delay(client.underlying.connectAsync[K, V](codec.underlying, uri))
      }
      .map(c => new Fs2Redis(new Fs2RedisStatefulConnection(c)))

    val release: Fs2Redis[F, K, V] => F[Unit] = c => Log[F].info(s"Releasing Commands connection: $uri") *> c.conn.close

    (acquire, release)
  }

  private[fs2redis] def acquireAndReleaseCluster[F[_]: Concurrent: Log, K, V](
      client: Fs2RedisClusterClient,
      codec: Fs2RedisCodec[K, V],
  ): (F[Fs2RedisCluster[F, K, V]], Fs2RedisCluster[F, K, V] => F[Unit]) = {
    val acquire = JRFuture
      .fromCompletableFuture {
        Sync[F].delay(client.underlying.connectAsync[K, V](codec.underlying))
      }
      .map(c => new Fs2RedisCluster(new Fs2RedisStatefulClusterConnection(c)))

    val release: Fs2RedisCluster[F, K, V] => F[Unit] = c =>
      Log[F].info(s"Releasing cluster Commands connection: ${client.underlying}") *> c.conn.close

    (acquire, release)
  }

  def apply[F[_]: Concurrent: Log, K, V](
      client: Fs2RedisClient,
      codec: Fs2RedisCodec[K, V],
      uri: RedisURI
  ): Resource[F, RedisCommands[F, K, V]] = {
    val (acquire, release) = acquireAndRelease(client, codec, uri)
    Resource.make(acquire)(release).map(_.asInstanceOf[RedisCommands[F, K, V]])
  }

  def cluster[F[_]: Concurrent: Log, K, V](
      clusterClient: Fs2RedisClusterClient,
      codec: Fs2RedisCodec[K, V],
      uri: RedisURI*
  ): Resource[F, RedisCommands[F, K, V]] = {
    val (acquire, release) = acquireAndReleaseCluster(clusterClient, codec)
    Resource.make(acquire)(release).map(_.asInstanceOf[RedisCommands[F, K, V]])
  }

  def masterSlave[F[_]: Concurrent: Log, K, V](conn: Fs2RedisMasterSlaveConnection[K, V]): F[RedisCommands[F, K, V]] =
    new Fs2Redis[F, K, V](new Fs2RedisStatefulConnection(conn.underlying)).asInstanceOf[RedisCommands[F, K, V]].pure[F]

}

private[fs2redis] class BaseFs2Redis[F[_], K, V](
    val conn: Fs2RedisConnection[F, K, V]
)(implicit F: Concurrent[F])
    extends RedisCommands[F, K, V]
    with Fs2RedisConversionOps {

  import scala.collection.JavaConverters._

  override def del(key: K*): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.del(key: _*)))
    }.void

  override def expire(key: K, expiresIn: FiniteDuration): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.expire(key, expiresIn.toSeconds)))
    }.void

  /******************************* Strings API **********************************/
  override def append(key: K, value: V): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.append(key, value)))
    }.void

  override def getSet(key: K, value: V): F[Option[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.getset(key, value)))
    }.map(Option.apply)

  override def set(key: K, value: V): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.set(key, value)))
    }.void

  override def setNx(key: K, value: V): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.setnx(key, value)))
    }.void

  override def setEx(key: K, value: V, expiresIn: FiniteDuration): F[Unit] = {
    val command = expiresIn.unit match {
      case TimeUnit.MILLISECONDS =>
        conn.async.flatMap(c => F.delay(c.psetex(key, expiresIn.toMillis, value)))
      case _ =>
        conn.async.flatMap(c => F.delay(c.setex(key, expiresIn.toSeconds, value)))
    }
    JRFuture(command).void
  }

  override def setRange(key: K, value: V, offset: Long): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.setrange(key, offset, value)))
    }.void

  override def decr(key: K)(implicit N: Numeric[V]): F[Long] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.decr(key)))
    }.map(x => Long.box(x))

  override def decrBy(key: K, amount: Long)(implicit N: Numeric[V]): F[Long] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.incrby(key, amount)))
    }.map(x => Long.box(x))

  override def incr(key: K)(implicit N: Numeric[V]): F[Long] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.incr(key)))
    }.map(x => Long.box(x))

  override def incrBy(key: K, amount: Long)(implicit N: Numeric[V]): F[Long] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.incrby(key, amount)))
    }.map(x => Long.box(x))

  override def incrByFloat(key: K, amount: Double)(implicit N: Numeric[V]): F[Double] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.incrbyfloat(key, amount)))
    }.map(x => Double.box(x))

  override def get(key: K): F[Option[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.get(key)))
    }.map(Option.apply)

  override def getBit(key: K, offset: Long): F[Option[Long]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.getbit(key, offset)))
    }.map(x => Option(Long.box(x)))

  override def getRange(key: K, start: Long, end: Long): F[Option[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.getrange(key, start, end)))
    }.map(Option.apply)

  override def strLen(key: K): F[Option[Long]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.strlen(key)))
    }.map(x => Option(Long.box(x)))

  override def mGet(keys: Set[K]): F[Map[K, V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.mget(keys.toSeq: _*)))
    }.map(_.asScala.toList.collect { case kv if kv.hasValue => kv.getKey -> kv.getValue }.toMap)

  override def mSet(keyValues: Map[K, V]): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.mset(keyValues.asJava)))
    }.void

  override def mSetNx(keyValues: Map[K, V]): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.msetnx(keyValues.asJava)))
    }.void

  override def bitCount(key: K): F[Long] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.bitcount(key)))
    }.map(x => Long.box(x))

  override def bitCount(key: K, start: Long, end: Long): F[Long] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.bitcount(key, start, end)))
    }.map(x => Long.box(x))

  override def bitPos(key: K, state: Boolean): F[Long] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.bitpos(key, state)))
    }.map(x => Long.box(x))

  override def bitPos(key: K, state: Boolean, start: Long): F[Long] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.bitpos(key, state, start)))
    }.map(x => Long.box(x))

  override def bitPos(key: K, state: Boolean, start: Long, end: Long): F[Long] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.bitpos(key, state, start, end)))
    }.map(x => Long.box(x))

  override def bitOpAnd(destination: K, sources: K*): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.bitopAnd(destination, sources: _*)))
    }.void

  override def bitOpNot(destination: K, source: K): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.bitopNot(destination, source)))
    }.void

  override def bitOpOr(destination: K, sources: K*): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.bitopOr(destination, sources: _*)))
    }.void

  override def bitOpXor(destination: K, sources: K*): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.bitopXor(destination, sources: _*)))
    }.void

  /******************************* Hashes API **********************************/
  override def hDel(key: K, fields: K*): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.hdel(key, fields: _*)))
    }.void

  override def hExists(key: K, field: K): F[Boolean] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.hexists(key, field)))
    }.map(x => Boolean.box(x))

  override def hGet(key: K, field: K): F[Option[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.hget(key, field)))
    }.map(Option.apply)

  override def hGetAll(key: K): F[Map[K, V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.hgetall(key)))
    }.map(_.asScala.toMap)

  override def hmGet(key: K, fields: K*): F[Map[K, V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.hmget(key, fields: _*)))
    }.map(_.asScala.toList.map(kv => kv.getKey -> kv.getValue).toMap)

  override def hKeys(key: K): F[List[K]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.hkeys(key)))
    }.map(_.asScala.toList)

  override def hVals(key: K): F[List[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.hvals(key)))
    }.map(_.asScala.toList)

  override def hStrLen(key: K, field: K): F[Option[Long]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.hstrlen(key, field)))
    }.map(x => Option(Long.box(x)))

  override def hLen(key: K): F[Option[Long]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.hlen(key)))
    }.map(x => Option(Long.box(x)))

  override def hSet(key: K, field: K, value: V): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.hset(key, field, value)))
    }.void

  override def hSetNx(key: K, field: K, value: V): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.hsetnx(key, field, value)))
    }.void

  override def hmSet(key: K, fieldValues: Map[K, V]): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.hmset(key, fieldValues.asJava)))
    }.void

  override def hIncrBy(key: K, field: K, amount: Long)(implicit N: Numeric[V]): F[Long] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.hincrby(key, field, amount)))
    }.map(x => Long.box(x))

  override def hIncrByFloat(key: K, field: K, amount: Double)(implicit N: Numeric[V]): F[Double] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.hincrbyfloat(key, field, amount)))
    }.map(x => Double.box(x))

  /******************************* Sets API **********************************/
  override def sIsMember(key: K, value: V): F[Boolean] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.sismember(key, value)))
    }.map(x => Boolean.box(x))

  override def sAdd(key: K, values: V*): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.sadd(key, values: _*)))
    }.void

  override def sDiffStore(destination: K, keys: K*): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.sdiffstore(destination, keys: _*)))
    }.void

  override def sInterStore(destination: K, keys: K*): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.sinterstore(destination, keys: _*)))
    }.void

  override def sMove(source: K, destination: K, value: V): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.smove(source, destination, value)))
    }.void

  override def sPop(key: K): F[Option[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.spop(key)))
    }.map(Option.apply)

  override def sPop(key: K, count: Long): F[Set[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.spop(key, count)))
    }.map(_.asScala.toSet)

  override def sRem(key: K, values: V*): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.srem(key, values: _*)))
    }.void

  override def sCard(key: K): F[Long] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.scard(key)))
    }.map(x => Long.box(x))

  override def sDiff(keys: K*): F[Set[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.sdiff(keys: _*)))
    }.map(_.asScala.toSet)

  override def sInter(keys: K*): F[Set[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.sinter(keys: _*)))
    }.map(_.asScala.toSet)

  override def sMembers(key: K): F[Set[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.smembers(key)))
    }.map(_.asScala.toSet)

  override def sRandMember(key: K): F[Option[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.srandmember(key)))
    }.map(Option.apply)

  override def sRandMember(key: K, count: Long): F[List[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.srandmember(key, count)))
    }.map(_.asScala.toList)

  override def sUnion(keys: K*): F[Set[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.sunion(keys: _*)))
    }.map(_.asScala.toSet)

  override def sUnionStore(destination: K, keys: K*): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.sunionstore(destination, keys: _*)))
    }.void

  /******************************* Lists API **********************************/
  override def lIndex(key: K, index: Long): F[Option[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.lindex(key, index)))
    }.map(Option.apply)

  override def lLen(key: K): F[Option[Long]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.llen(key)))
    }.map(x => Option(Long.box(x)))

  override def lRange(key: K, start: Long, stop: Long): F[List[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.lrange(key, start, stop)))
    }.map(_.asScala.toList)

  override def blPop(timeout: FiniteDuration, keys: K*): F[(K, V)] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.blpop(timeout.toMillis, keys: _*)))
    }.map(kv => kv.getKey -> kv.getValue)

  override def brPop(timeout: FiniteDuration, keys: K*): F[(K, V)] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.brpop(timeout.toMillis, keys: _*)))
    }.map(kv => kv.getKey -> kv.getValue)

  override def brPopLPush(timeout: FiniteDuration, source: K, destination: K): F[Option[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.brpoplpush(timeout.toMillis, source, destination)))
    }.map(Option.apply)

  override def lPop(key: K): F[Option[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.lpop(key)))
    }.map(Option.apply)

  override def lPush(key: K, values: V*): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.lpush(key, values: _*)))
    }.void

  override def lPushX(key: K, values: V*): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.lpushx(key, values: _*)))
    }.void

  override def rPop(key: K): F[Option[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.rpop(key)))
    }.map(Option.apply)

  override def rPopLPush(source: K, destination: K): F[Option[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.rpoplpush(source, destination)))
    }.map(Option.apply)

  override def rPush(key: K, values: V*): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.rpush(key, values: _*)))
    }.void

  override def rPushX(key: K, values: V*): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.rpushx(key, values: _*)))
    }.void

  override def lInsertAfter(key: K, pivot: V, value: V): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.linsert(key, false, pivot, value)))
    }.void

  override def lInsertBefore(key: K, pivot: V, value: V): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.linsert(key, true, pivot, value)))
    }.void

  override def lRem(key: K, count: Long, value: V): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.lrem(key, count, value)))
    }.void

  override def lSet(key: K, index: Long, value: V): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.lset(key, index, value)))
    }.void

  override def lTrim(key: K, start: Long, stop: Long): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.ltrim(key, start, stop)))
    }.void

  /******************************* Geo API **********************************/
  override def geoDist(key: K, from: V, to: V, unit: GeoArgs.Unit): F[Double] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.geodist(key, from, to, unit)))
    }.map(x => Double.box(x))

  override def geoHash(key: K, values: V*): F[List[Option[String]]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.geohash(key, values: _*)))
    }.map(_.asScala.toList.map(x => Option(x.getValue)))

  override def geoPos(key: K, values: V*): F[List[GeoCoordinate]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.geopos(key, values: _*)))
    }.map(_.asScala.toList.map(c => GeoCoordinate(c.getX.doubleValue(), c.getY.doubleValue())))

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit): F[Set[V]] =
    JRFuture {
      conn.async.flatMap(
        c => F.delay(c.georadius(key, geoRadius.lon.value, geoRadius.lat.value, geoRadius.dist.value, unit))
      )
    }.map(_.asScala.toSet)

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit, args: GeoArgs): F[List[GeoRadiusResult[V]]] =
    JRFuture {
      conn.async.flatMap(
        c => F.delay(c.georadius(key, geoRadius.lon.value, geoRadius.lat.value, geoRadius.dist.value, unit, args))
      )
    }.map(_.asScala.toList.map(_.asGeoRadiusResult))

  override def geoRadiusByMember(key: K, value: V, dist: Distance, unit: GeoArgs.Unit): F[Set[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.georadiusbymember(key, value, dist.value, unit)))
    }.map(_.asScala.toSet)

  override def geoRadiusByMember(key: K,
                                 value: V,
                                 dist: Distance,
                                 unit: GeoArgs.Unit,
                                 args: GeoArgs): F[List[GeoRadiusResult[V]]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.georadiusbymember(key, value, dist.value, unit, args)))
    }.map(_.asScala.toList.map(_.asGeoRadiusResult))

  override def geoAdd(key: K, geoValues: GeoLocation[V]*): F[Unit] =
    JRFuture {
      val triplets = geoValues.flatMap(g => Seq(g.lon.value, g.lat.value, g.value)).asInstanceOf[Seq[AnyRef]]
      conn.async.flatMap(c => F.delay(c.geoadd(key, triplets: _*)))
    }.void

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit, storage: GeoRadiusKeyStorage[K]): F[Unit] =
    JRFuture {
      conn.async
        .flatMap { c =>
          F.delay {
            c.georadius(
              key,
              geoRadius.lon.value,
              geoRadius.lat.value,
              geoRadius.dist.value,
              unit,
              storage.asGeoRadiusStoreArgs
            )
          }
        }
    }.void

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit, storage: GeoRadiusDistStorage[K]): F[Unit] =
    JRFuture {
      conn.async
        .flatMap { c =>
          F.delay {
            c.georadius(
              key,
              geoRadius.lon.value,
              geoRadius.lat.value,
              geoRadius.dist.value,
              unit,
              storage.asGeoRadiusStoreArgs
            )
          }
        }
    }.void

  override def geoRadiusByMember(key: K,
                                 value: V,
                                 dist: Distance,
                                 unit: GeoArgs.Unit,
                                 storage: GeoRadiusKeyStorage[K]): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.georadiusbymember(key, value, dist.value, unit, storage.asGeoRadiusStoreArgs)))
    }.void

  override def geoRadiusByMember(key: K,
                                 value: V,
                                 dist: Distance,
                                 unit: GeoArgs.Unit,
                                 storage: GeoRadiusDistStorage[K]): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.georadiusbymember(key, value, dist.value, unit, storage.asGeoRadiusStoreArgs)))
    }.void

  /******************************* Sorted Sets API **********************************/
  override def zAdd(key: K, args: Option[ZAddArgs], values: ScoreWithValue[V]*): F[Unit] =
    JRFuture {
      args match {
        case Some(x) =>
          conn.async.flatMap(
            c => F.delay(c.zadd(key, x, values.map(s => ScoredValue.just(s.score.value, s.value)): _*))
          )
        case None =>
          conn.async.flatMap(c => F.delay(c.zadd(key, values.map(s => ScoredValue.just(s.score.value, s.value)): _*)))
      }
    }.void

  override def zAddIncr(key: K, args: Option[ZAddArgs], member: ScoreWithValue[V])(implicit ev: Numeric[V]): F[Unit] =
    JRFuture {
      args match {
        case Some(x) => conn.async.flatMap(c => F.delay(c.zaddincr(key, x, member.score.value, member.value)))
        case None    => conn.async.flatMap(c => F.delay(c.zaddincr(key, member.score.value, member.value)))
      }
    }.void

  override def zIncrBy(key: K, member: V, amount: Double): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.zincrby(key, amount, member)))
    }.void

  override def zInterStore(destination: K, args: Option[ZStoreArgs], keys: K*): F[Unit] =
    JRFuture {
      args match {
        case Some(x) => conn.async.flatMap(c => F.delay(c.zinterstore(destination, x, keys: _*)))
        case None    => conn.async.flatMap(c => F.delay(c.zinterstore(destination, keys: _*)))
      }
    }.void

  override def zRem(key: K, values: V*): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.zrem(key, values: _*)))
    }.void

  override def zRemRangeByLex(key: K, range: ZRange[V]): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.zremrangebylex(key, Range.create[V](range.start, range.end))))
    }.void

  override def zRemRangeByRank(key: K, start: Long, stop: Long): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.zremrangebyrank(key, start, stop)))
    }.void

  override def zRemRangeByScore(key: K, range: ZRange[V])(implicit ev: Numeric[V]): F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.zremrangebyscore(key, range.asJavaRange)))
    }.void

  override def zUnionStore(destination: K, args: Option[ZStoreArgs], keys: K*): F[Unit] =
    JRFuture {
      args match {
        case Some(x) => conn.async.flatMap(c => F.delay(c.zunionstore(destination, x, keys: _*)))
        case None    => conn.async.flatMap(c => F.delay(c.zunionstore(destination, keys: _*)))
      }
    }.void

  override def zCard(key: K): F[Option[Long]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.zcard(key)))
    }.map(x => Option(Long.box(x)))

  override def zCount(key: K, range: ZRange[V])(implicit ev: Numeric[V]): F[Option[Long]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.zcount(key, range.asJavaRange)))
    }.map(x => Option(Long.box(x)))

  override def zLexCount(key: K, range: ZRange[V]): F[Option[Long]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.zlexcount(key, Range.create[V](range.start, range.end))))
    }.map(x => Option(Long.box(x)))

  override def zRange(key: K, start: Long, stop: Long): F[List[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.zrange(key, start, stop)))
    }.map(_.asScala.toList)

  override def zRangeByLex(key: K, range: ZRange[V], limit: Option[RangeLimit]): F[List[V]] =
    JRFuture {
      limit match {
        case Some(x) =>
          conn.async.flatMap(
            c => F.delay(c.zrangebylex(key, Range.create[V](range.start, range.end), Limit.create(x.offset, x.count)))
          )
        case None => conn.async.flatMap(c => F.delay(c.zrangebylex(key, Range.create[V](range.start, range.end))))
      }
    }.map(_.asScala.toList)

  override def zRangeByScore(key: K, range: ZRange[V], limit: Option[RangeLimit])(implicit ev: Numeric[V]): F[List[V]] =
    JRFuture {
      limit match {
        case Some(x) =>
          conn.async.flatMap(c => F.delay(c.zrangebyscore(key, range.asJavaRange, Limit.create(x.offset, x.count))))
        case None => conn.async.flatMap(c => F.delay(c.zrangebyscore(key, range.asJavaRange)))
      }
    }.map(_.asScala.toList)

  override def zRangeByScoreWithScores(key: K, range: ZRange[V], limit: Option[RangeLimit])(
      implicit ev: Numeric[V]
  ): F[List[ScoreWithValue[V]]] =
    JRFuture {
      limit match {
        case Some(x) =>
          conn.async.flatMap(
            c => F.delay(c.zrangebyscoreWithScores(key, range.asJavaRange, Limit.create(x.offset, x.count)))
          )
        case None => conn.async.flatMap(c => F.delay(c.zrangebyscoreWithScores(key, range.asJavaRange)))
      }
    }.map(_.asScala.toList.map(_.asScoreWithValues))

  override def zRangeWithScores(key: K, start: Long, stop: Long): F[List[ScoreWithValue[V]]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.zrangeWithScores(key, start, stop)))
    }.map(_.asScala.toList.map(_.asScoreWithValues))

  override def zRank(key: K, value: V): F[Option[Long]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.zrank(key, value)))
    }.map(x => Option(Long.box(x)))

  override def zRevRange(key: K, start: Long, stop: Long): F[List[V]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.zrevrange(key, start, stop)))
    }.map(_.asScala.toList)

  override def zRevRangeByLex(key: K, range: ZRange[V], limit: Option[RangeLimit]): F[List[V]] =
    JRFuture {
      limit match {
        case Some(x) =>
          conn.async.flatMap(
            c =>
              F.delay(c.zrevrangebylex(key, Range.create[V](range.start, range.end), Limit.create(x.offset, x.count)))
          )
        case None => conn.async.flatMap(c => F.delay(c.zrevrangebylex(key, Range.create[V](range.start, range.end))))
      }
    }.map(_.asScala.toList)

  override def zRevRangeByScore(key: K, range: ZRange[V], limit: Option[RangeLimit])(
      implicit ev: Numeric[V]
  ): F[List[V]] =
    JRFuture {
      limit match {
        case Some(x) =>
          conn.async.flatMap(c => F.delay(c.zrevrangebyscore(key, range.asJavaRange, Limit.create(x.offset, x.count))))
        case None => conn.async.flatMap(c => F.delay(c.zrevrangebyscore(key, range.asJavaRange)))
      }
    }.map(_.asScala.toList)

  override def zRevRangeByScoreWithScores(key: K, range: ZRange[V], limit: Option[RangeLimit])(
      implicit ev: Numeric[V]
  ): F[List[ScoreWithValue[V]]] =
    JRFuture {
      limit match {
        case Some(x) =>
          conn.async.flatMap(
            c => F.delay(c.zrangebyscoreWithScores(key, range.asJavaRange, Limit.create(x.offset, x.count)))
          )
        case None => conn.async.flatMap(c => F.delay(c.zrangebyscoreWithScores(key, range.asJavaRange)))
      }
    }.map(_.asScala.toList.map(_.asScoreWithValues))

  override def zRevRangeWithScores(key: K, start: Long, stop: Long): F[List[ScoreWithValue[V]]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.zrangeWithScores(key, start, stop)))
    }.map(_.asScala.toList.map(_.asScoreWithValues))

  override def zRevRank(key: K, value: V): F[Option[Long]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.zrevrank(key, value)))
    }.map(x => Option(Long.box(x)))

  override def zScore(key: K, value: V): F[Option[Double]] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.zscore(key, value)))
    }.map(x => Option(Double.box(x)))

  /******************************* Connection API **********************************/
  override val ping: F[String] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.ping()))
    }

  /******************************* Server API **********************************/
  override val flushAll: F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.flushall()))
    }.void

  override val flushAllAsync: F[Unit] =
    JRFuture {
      conn.async.flatMap(c => F.delay(c.flushallAsync()))
    }.void
}

private[fs2redis] trait Fs2RedisConversionOps {

  private[fs2redis] implicit class GeoRadiusResultOps[V](v: GeoWithin[V]) {
    def asGeoRadiusResult: GeoRadiusResult[V] =
      GeoRadiusResult[V](
        v.getMember,
        Distance(v.getDistance),
        GeoHash(v.getGeohash),
        GeoCoordinate(v.getCoordinates.getX.doubleValue(), v.getCoordinates.getY.doubleValue())
      )
  }

  private[fs2redis] implicit class GeoRadiusKeyStorageOps[K](v: GeoRadiusKeyStorage[K]) {
    def asGeoRadiusStoreArgs: GeoRadiusStoreArgs[K] = {
      val store: GeoRadiusStoreArgs[_] = GeoRadiusStoreArgs.Builder
        .store[K](v.key)
        .withCount(v.count)
      store.asInstanceOf[GeoRadiusStoreArgs[K]]
    }
  }

  private[fs2redis] implicit class GeoRadiusDistStorageOps[K](v: GeoRadiusDistStorage[K]) {
    def asGeoRadiusStoreArgs: GeoRadiusStoreArgs[K] = {
      val store: GeoRadiusStoreArgs[_] = GeoRadiusStoreArgs.Builder
        .withStoreDist[K](v.key)
        .withCount(v.count)
      store.asInstanceOf[GeoRadiusStoreArgs[K]]
    }
  }

  private[fs2redis] implicit class ZRangeOps[V: Numeric](range: ZRange[V]) {
    def asJavaRange: Range[Number] = {
      val start: Number = range.start.asInstanceOf[java.lang.Number]
      val end: Number   = range.end.asInstanceOf[java.lang.Number]
      Range.create(start, end)
    }
  }

  private[fs2redis] implicit class ScoredValuesOps[V](v: ScoredValue[V]) {
    def asScoreWithValues: ScoreWithValue[V] = ScoreWithValue[V](Score(v.getScore), v.getValue)
  }

}

private[fs2redis] class Fs2Redis[F[_]: Concurrent, K, V](
    connection: Fs2RedisStatefulConnection[F, K, V]
) extends BaseFs2Redis[F, K, V](connection)

private[fs2redis] class Fs2RedisCluster[F[_]: Concurrent, K, V](
    connection: Fs2RedisStatefulClusterConnection[F, K, V]
) extends BaseFs2Redis[F, K, V](connection)
