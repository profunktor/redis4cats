/*
 * Copyright 2018 Fs2 Redis
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

import cats.effect.{Concurrent, Resource}
import cats.syntax.apply._
import cats.syntax.functor._
import com.github.gvolpe.fs2redis.algebra._
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis.RedisCommands
import com.github.gvolpe.fs2redis.model._
import com.github.gvolpe.fs2redis.util.{JRFuture, Log}
import fs2.Stream
import io.lettuce.core._
import io.lettuce.core.api.StatefulRedisConnection

import scala.concurrent.duration.FiniteDuration

object Fs2Redis {

  trait RedisCommands[F[_], K, V]
      extends StringCommands[F, K, V]
      with HashCommands[F, K, V]
      with SetCommands[F, K, V]
      with SortedSetCommands[F, K, V]
      with ListCommands[F, K, V]
      with GeoCommands[F, K, V]

  private[fs2redis] def acquireAndRelease[F[_], K, V](
      client: Fs2RedisClient,
      codec: Fs2RedisCodec[K, V],
      uri: RedisURI)(implicit F: Concurrent[F], L: Log[F]): (F[Fs2Redis[F, K, V]], Fs2Redis[F, K, V] => F[Unit]) = {
    val acquire = JRFuture
      .fromConnectionFuture {
        F.delay(client.underlying.connectAsync[K, V](codec.underlying, uri))
      }
      .map(c => new Fs2Redis(c))

    val release: Fs2Redis[F, K, V] => F[Unit] = c =>
      JRFuture.fromCompletableFuture(F.delay(c.client.closeAsync())) *>
        L.info(s"Releasing Commands connection: $uri")

    (acquire, release)
  }

  def apply[F[_]: Concurrent: Log, K, V](client: Fs2RedisClient,
                                         codec: Fs2RedisCodec[K, V],
                                         uri: RedisURI): Resource[F, RedisCommands[F, K, V]] = {
    val (acquire, release) = acquireAndRelease(client, codec, uri)
    Resource.make(acquire)(release).map(_.asInstanceOf[RedisCommands[F, K, V]])
  }

  def stream[F[_]: Concurrent: Log, K, V](client: Fs2RedisClient,
                                          codec: Fs2RedisCodec[K, V],
                                          uri: RedisURI): Stream[F, RedisCommands[F, K, V]] = {
    val (acquire, release) = acquireAndRelease(client, codec, uri)
    Stream.bracket(acquire)(release)
  }

}

private[fs2redis] class Fs2Redis[F[_], K, V](val client: StatefulRedisConnection[K, V])(implicit F: Concurrent[F])
    extends RedisCommands[F, K, V]
    with Fs2RedisConversionOps {

  import scala.collection.JavaConverters._

  override def del(key: K*): F[Unit] =
    JRFuture {
      F.delay(client.async().del(key: _*))
    }.void

  override def expire(key: K, expiresIn: FiniteDuration): F[Unit] =
    JRFuture {
      F.delay(client.async().expire(key, expiresIn.toSeconds))
    }.void

  /******************************* Strings API **********************************/
  override def append(key: K, value: V): F[Unit] =
    JRFuture {
      F.delay(client.async().append(key, value))
    }.void

  override def getSet(key: K, value: V): F[Option[V]] =
    JRFuture {
      F.delay(client.async().getset(key, value))
    }.map(Option.apply)

  override def set(key: K, value: V): F[Unit] =
    JRFuture {
      F.delay(client.async().set(key, value))
    }.void

  override def setNx(key: K, value: V): F[Unit] =
    JRFuture {
      F.delay(client.async().setnx(key, value))
    }.void

  override def setEx(key: K, value: V, expiresIn: FiniteDuration): F[Unit] = {
    val command = expiresIn.unit match {
      case TimeUnit.MILLISECONDS =>
        F.delay(client.async().psetex(key, expiresIn.toMillis, value))
      case _ =>
        F.delay(client.async().setex(key, expiresIn.toSeconds, value))
    }
    JRFuture(command).void
  }

  override def setRange(key: K, value: V, offset: Long): F[Unit] =
    JRFuture {
      F.delay(client.async().setrange(key, offset, value))
    }.void

  override def decr(key: K)(implicit N: Numeric[V]): F[Long] =
    JRFuture {
      F.delay(client.async().decr(key))
    }.map(x => Long.box(x))

  override def decrBy(key: K, amount: Long)(implicit N: Numeric[V]): F[Long] =
    JRFuture {
      F.delay(client.async().incrby(key, amount))
    }.map(x => Long.box(x))

  override def incr(key: K)(implicit N: Numeric[V]): F[Long] =
    JRFuture {
      F.delay(client.async().incr(key))
    }.map(x => Long.box(x))

  override def incrBy(key: K, amount: Long)(implicit N: Numeric[V]): F[Long] =
    JRFuture {
      F.delay(client.async().incrby(key, amount))
    }.map(x => Long.box(x))

  override def incrByFloat(key: K, amount: Double)(implicit N: Numeric[V]): F[Double] =
    JRFuture {
      F.delay(client.async().incrbyfloat(key, amount))
    }.map(x => Double.box(x))

  override def get(key: K): F[Option[V]] =
    JRFuture {
      F.delay(client.async().get(key))
    }.map(Option.apply)

  override def getBit(key: K, offset: Long): F[Option[Long]] =
    JRFuture {
      F.delay(client.async().getbit(key, offset))
    }.map(x => Option(Long.box(x)))

  override def getRange(key: K, start: Long, end: Long): F[Option[V]] =
    JRFuture {
      F.delay(client.async().getrange(key, start, end))
    }.map(Option.apply)

  override def strLen(key: K): F[Option[Long]] =
    JRFuture {
      F.delay(client.async().strlen(key))
    }.map(x => Option(Long.box(x)))

  override def mGet(keys: Set[K]): F[Map[K, V]] =
    JRFuture {
      F.delay(client.async().mget(keys.toSeq: _*))
    }.map(_.asScala.toList.collect { case kv if kv.hasValue => kv.getKey -> kv.getValue }.toMap)

  override def mSet(keyValues: Map[K, V]): F[Unit] =
    JRFuture {
      F.delay(client.async().mset(keyValues.asJava))
    }.void

  override def mSetNx(keyValues: Map[K, V]): F[Unit] =
    JRFuture {
      F.delay(client.async().msetnx(keyValues.asJava))
    }.void

  override def bitCount(key: K): F[Long] =
    JRFuture {
      F.delay(client.async().bitcount(key))
    }.map(x => Long.box(x))

  override def bitCount(key: K, start: Long, end: Long): F[Long] =
    JRFuture {
      F.delay(client.async().bitcount(key, start, end))
    }.map(x => Long.box(x))

  override def bitPos(key: K, state: Boolean): F[Long] =
    JRFuture {
      F.delay(client.async().bitpos(key, state))
    }.map(x => Long.box(x))

  override def bitPos(key: K, state: Boolean, start: Long): F[Long] =
    JRFuture {
      F.delay(client.async().bitpos(key, state, start))
    }.map(x => Long.box(x))

  override def bitPos(key: K, state: Boolean, start: Long, end: Long): F[Long] =
    JRFuture {
      F.delay(client.async().bitpos(key, state, start, end))
    }.map(x => Long.box(x))

  override def bitOpAnd(destination: K, sources: K*): F[Unit] =
    JRFuture {
      F.delay(client.async().bitopAnd(destination, sources: _*))
    }.void

  override def bitOpNot(destination: K, source: K): F[Unit] =
    JRFuture {
      F.delay(client.async().bitopNot(destination, source))
    }.void

  override def bitOpOr(destination: K, sources: K*): F[Unit] =
    JRFuture {
      F.delay(client.async().bitopOr(destination, sources: _*))
    }.void

  override def bitOpXor(destination: K, sources: K*): F[Unit] =
    JRFuture {
      F.delay(client.async().bitopXor(destination, sources: _*))
    }.void

  /******************************* Hashes API **********************************/
  override def hDel(key: K, fields: K*): F[Unit] =
    JRFuture {
      F.delay(client.async().hdel(key, fields: _*))
    }.void

  override def hExists(key: K, field: K): F[Boolean] =
    JRFuture {
      F.delay(client.async().hexists(key, field))
    }.map(x => Boolean.box(x))

  override def hGet(key: K, field: K): F[Option[V]] =
    JRFuture {
      F.delay(client.async().hget(key, field))
    }.map(Option.apply)

  override def hGetAll(key: K): F[Map[K, V]] =
    JRFuture {
      F.delay(client.async().hgetall(key))
    }.map(_.asScala.toMap)

  override def hmGet(key: K, fields: K*): F[Map[K, V]] =
    JRFuture {
      F.delay(client.async().hmget(key, fields: _*))
    }.map(_.asScala.toList.map(kv => kv.getKey -> kv.getValue).toMap)

  override def hKeys(key: K): F[List[K]] =
    JRFuture {
      F.delay(client.async().hkeys(key))
    }.map(_.asScala.toList)

  override def hVals(key: K): F[List[V]] =
    JRFuture {
      F.delay(client.async().hvals(key))
    }.map(_.asScala.toList)

  override def hStrLen(key: K, field: K): F[Option[Long]] =
    JRFuture {
      F.delay(client.async().hstrlen(key, field))
    }.map(x => Option(Long.box(x)))

  override def hLen(key: K): F[Option[Long]] =
    JRFuture {
      F.delay(client.async().hlen(key))
    }.map(x => Option(Long.box(x)))

  override def hSet(key: K, field: K, value: V): F[Unit] =
    JRFuture {
      F.delay(client.async().hset(key, field, value))
    }.void

  override def hSetNx(key: K, field: K, value: V): F[Unit] =
    JRFuture {
      F.delay(client.async().hsetnx(key, field, value))
    }.void

  override def hmSet(key: K, fieldValues: Map[K, V]): F[Unit] =
    JRFuture {
      F.delay(client.async().hmset(key, fieldValues.asJava))
    }.void

  override def hIncrBy(key: K, field: K, amount: Long)(implicit N: Numeric[V]): F[Long] =
    JRFuture {
      F.delay(client.async().hincrby(key, field, amount))
    }.map(x => Long.box(x))

  override def hIncrByFloat(key: K, field: K, amount: Double)(implicit N: Numeric[V]): F[Double] =
    JRFuture {
      F.delay(client.async().hincrbyfloat(key, field, amount))
    }.map(x => Double.box(x))

  /******************************* Sets API **********************************/
  override def sIsMember(key: K, value: V): F[Boolean] =
    JRFuture {
      F.delay(client.async().sismember(key, value))
    }.map(x => Boolean.box(x))

  override def sAdd(key: K, values: V*): F[Unit] =
    JRFuture {
      F.delay(client.async().sadd(key, values: _*))
    }.void

  override def sDiffStore(destination: K, keys: K*): F[Unit] =
    JRFuture {
      F.delay(client.async().sdiffstore(destination, keys: _*))
    }.void

  override def sInterStore(destination: K, keys: K*): F[Unit] =
    JRFuture {
      F.delay(client.async().sinterstore(destination, keys: _*))
    }.void

  override def sMove(source: K, destination: K, value: V): F[Unit] =
    JRFuture {
      F.delay(client.async().smove(source, destination, value))
    }.void

  override def sPop(key: K): F[Option[V]] =
    JRFuture {
      F.delay(client.async().spop(key))
    }.map(Option.apply)

  override def sPop(key: K, count: Long): F[Set[V]] =
    JRFuture {
      F.delay(client.async().spop(key, count))
    }.map(_.asScala.toSet)

  override def sRem(key: K, values: V*): F[Unit] =
    JRFuture {
      F.delay(client.async().srem(key, values: _*))
    }.void

  override def sCard(key: K): F[Long] =
    JRFuture {
      F.delay(client.async().scard(key))
    }.map(x => Long.box(x))

  override def sDiff(keys: K*): F[Set[V]] =
    JRFuture {
      F.delay(client.async().sdiff(keys: _*))
    }.map(_.asScala.toSet)

  override def sInter(keys: K*): F[Set[V]] =
    JRFuture {
      F.delay(client.async().sinter(keys: _*))
    }.map(_.asScala.toSet)

  override def sMembers(key: K): F[Set[V]] =
    JRFuture {
      F.delay(client.async().smembers(key))
    }.map(_.asScala.toSet)

  override def sRandMember(key: K): F[Option[V]] =
    JRFuture {
      F.delay(client.async().srandmember(key))
    }.map(Option.apply)

  override def sRandMember(key: K, count: Long): F[List[V]] =
    JRFuture {
      F.delay(client.async().srandmember(key, count))
    }.map(_.asScala.toList)

  override def sUnion(keys: K*): F[Set[V]] =
    JRFuture {
      F.delay(client.async().sunion(keys: _*))
    }.map(_.asScala.toSet)

  override def sUnionStore(destination: K, keys: K*): F[Unit] =
    JRFuture {
      F.delay(client.async().sunionstore(destination, keys: _*))
    }.void

  /******************************* Lists API **********************************/
  override def lIndex(key: K, index: Long): F[Option[V]] =
    JRFuture {
      F.delay(client.async().lindex(key, index))
    }.map(Option.apply)

  override def lLen(key: K): F[Option[Long]] =
    JRFuture {
      F.delay(client.async().llen(key))
    }.map(x => Option(Long.box(x)))

  override def lRange(key: K, start: Long, stop: Long): F[List[V]] =
    JRFuture {
      F.delay(client.async().lrange(key, start, stop))
    }.map(_.asScala.toList)

  override def blPop(timeout: FiniteDuration, keys: K*): F[(K, V)] =
    JRFuture {
      F.delay(client.async().blpop(timeout.toMillis, keys: _*))
    }.map(kv => kv.getKey -> kv.getValue)

  override def brPop(timeout: FiniteDuration, keys: K*): F[(K, V)] =
    JRFuture {
      F.delay(client.async().brpop(timeout.toMillis, keys: _*))
    }.map(kv => kv.getKey -> kv.getValue)

  override def brPopLPush(timeout: FiniteDuration, source: K, destination: K): F[Option[V]] =
    JRFuture {
      F.delay(client.async().brpoplpush(timeout.toMillis, source, destination))
    }.map(Option.apply)

  override def lPop(key: K): F[Option[V]] =
    JRFuture {
      F.delay(client.async().lpop(key))
    }.map(Option.apply)

  override def lPush(key: K, values: V*): F[Unit] =
    JRFuture {
      F.delay(client.async().lpush(key, values: _*))
    }.void

  override def lPushX(key: K, values: V*): F[Unit] =
    JRFuture {
      F.delay(client.async().lpushx(key, values: _*))
    }.void

  override def rPop(key: K): F[Option[V]] =
    JRFuture {
      F.delay(client.async().rpop(key))
    }.map(Option.apply)

  override def rPopLPush(source: K, destination: K): F[Option[V]] =
    JRFuture {
      F.delay(client.async().rpoplpush(source, destination))
    }.map(Option.apply)

  override def rPush(key: K, values: V*): F[Unit] =
    JRFuture {
      F.delay(client.async().rpush(key, values: _*))
    }.void

  override def rPushX(key: K, values: V*): F[Unit] =
    JRFuture {
      F.delay(client.async().rpushx(key, values: _*))
    }.void

  override def lInsertAfter(key: K, pivot: V, value: V): F[Unit] =
    JRFuture {
      F.delay(client.async().linsert(key, false, pivot, value))
    }.void

  override def lInsertBefore(key: K, pivot: V, value: V): F[Unit] =
    JRFuture {
      F.delay(client.async().linsert(key, true, pivot, value))
    }.void

  override def lRem(key: K, count: Long, value: V): F[Unit] =
    JRFuture {
      F.delay(client.async().lrem(key, count, value))
    }.void

  override def lSet(key: K, index: Long, value: V): F[Unit] =
    JRFuture {
      F.delay(client.async().lset(key, index, value))
    }.void

  override def lTrim(key: K, start: Long, stop: Long): F[Unit] =
    JRFuture {
      F.delay(client.async().ltrim(key, start, stop))
    }.void

  /******************************* Geo API **********************************/
  override def geoDist(key: K, from: V, to: V, unit: GeoArgs.Unit): F[Double] =
    JRFuture {
      F.delay(client.async().geodist(key, from, to, unit))
    }.map(x => Double.box(x))

  override def geoHash(key: K, values: V*): F[List[Option[String]]] =
    JRFuture {
      F.delay(client.async().geohash(key, values: _*))
    }.map(_.asScala.toList.map(x => Option(x.getValue)))

  override def geoPos(key: K, values: V*): F[List[GeoCoordinate]] =
    JRFuture {
      F.delay(client.async().geopos(key, values: _*))
    }.map(_.asScala.toList.map(c => GeoCoordinate(c.getX.doubleValue(), c.getY.doubleValue())))

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit): F[Set[V]] =
    JRFuture {
      F.delay(client.async().georadius(key, geoRadius.lon.value, geoRadius.lat.value, geoRadius.dist.value, unit))
    }.map(_.asScala.toSet)

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit, args: GeoArgs): F[List[GeoRadiusResult[V]]] =
    JRFuture {
      F.delay(client.async().georadius(key, geoRadius.lon.value, geoRadius.lat.value, geoRadius.dist.value, unit, args))
    }.map(_.asScala.toList.map(_.asGeoRadiusResult))

  override def geoRadiusByMember(key: K, value: V, dist: Distance, unit: GeoArgs.Unit): F[Set[V]] =
    JRFuture {
      F.delay(client.async().georadiusbymember(key, value, dist.value, unit))
    }.map(_.asScala.toSet)

  override def geoRadiusByMember(key: K,
                                 value: V,
                                 dist: Distance,
                                 unit: GeoArgs.Unit,
                                 args: GeoArgs): F[List[GeoRadiusResult[V]]] =
    JRFuture {
      F.delay(client.async().georadiusbymember(key, value, dist.value, unit, args))
    }.map(_.asScala.toList.map(_.asGeoRadiusResult))

  override def geoAdd(key: K, geoValues: GeoLocation[V]*): F[Unit] =
    JRFuture {
      val triplets = geoValues.flatMap(g => Seq(g.lon.value, g.lat.value, g.value)).asInstanceOf[Seq[AnyRef]]
      F.delay(client.async().geoadd(key, triplets: _*))
    }.void

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit, storage: GeoRadiusKeyStorage[K]): F[Unit] =
    JRFuture {
      F.delay {
        client
          .async()
          .georadius(key,
                     geoRadius.lon.value,
                     geoRadius.lat.value,
                     geoRadius.dist.value,
                     unit,
                     storage.asGeoRadiusStoreArgs)
      }
    }.void

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit, storage: GeoRadiusDistStorage[K]): F[Unit] =
    JRFuture {
      F.delay {
        client
          .async()
          .georadius(key,
                     geoRadius.lon.value,
                     geoRadius.lat.value,
                     geoRadius.dist.value,
                     unit,
                     storage.asGeoRadiusStoreArgs)
      }
    }.void

  override def geoRadiusByMember(key: K,
                                 value: V,
                                 dist: Distance,
                                 unit: GeoArgs.Unit,
                                 storage: GeoRadiusKeyStorage[K]): F[Unit] =
    JRFuture {
      F.delay {
        client.async().georadiusbymember(key, value, dist.value, unit, storage.asGeoRadiusStoreArgs)
      }
    }.void

  override def geoRadiusByMember(key: K,
                                 value: V,
                                 dist: Distance,
                                 unit: GeoArgs.Unit,
                                 storage: GeoRadiusDistStorage[K]): F[Unit] =
    JRFuture {
      F.delay {
        client.async().georadiusbymember(key, value, dist.value, unit, storage.asGeoRadiusStoreArgs)
      }
    }.void

  /******************************* Sorted Sets API **********************************/
  override def zAdd(key: K, args: Option[ZAddArgs], values: ScoreWithValue[V]*): F[Unit] =
    JRFuture {
      F.delay {
        args match {
          case Some(x) => client.async().zadd(key, x, values.map(s => ScoredValue.just(s.score.value, s.value)): _*)
          case None    => client.async().zadd(key, values.map(s => ScoredValue.just(s.score.value, s.value)): _*)
        }
      }
    }.void

  override def zAddIncr(key: K, args: Option[ZAddArgs], member: ScoreWithValue[V])(implicit ev: Numeric[V]): F[Unit] =
    JRFuture {
      F.delay {
        args match {
          case Some(x) => client.async().zaddincr(key, x, member.score.value, member.value)
          case None    => client.async().zaddincr(key, member.score.value, member.value)
        }
      }
    }.void

  override def zIncrBy(key: K, member: K, amount: Double): F[Unit] =
    JRFuture {
      F.delay {
        client.async().zincrby(key, amount, member)
      }
    }.void

  override def zInterStore(destination: K, args: Option[ZStoreArgs], keys: K*): F[Unit] =
    JRFuture {
      F.delay {
        args match {
          case Some(x) => client.async().zinterstore(destination, x, keys: _*)
          case None    => client.async().zinterstore(destination, keys: _*)
        }
      }
    }.void

  override def zRem(key: K, values: V*): F[Unit] =
    JRFuture {
      F.delay {
        client.async().zrem(key, values: _*)
      }
    }.void

  override def zRemRangeByLex(key: K, range: ZRange[V]): F[Unit] =
    JRFuture {
      F.delay {
        client.async().zremrangebylex(key, Range.create[V](range.start, range.end))
      }
    }.void

  override def zRemRangeByRank(key: K, start: Long, stop: Long): F[Unit] =
    JRFuture {
      F.delay {
        client.async().zremrangebyrank(key, start, stop)
      }
    }.void

  override def zRemRangeByScore(key: K, range: ZRange[V])(implicit ev: Numeric[V]): F[Unit] =
    JRFuture {
      F.delay {
        client.async().zremrangebyscore(key, range.asJavaRange)
      }
    }.void

  override def zUnionStore(destination: K, args: Option[ZStoreArgs], keys: K*): F[Unit] =
    JRFuture {
      F.delay {
        args match {
          case Some(x) => client.async().zunionstore(destination, x, keys: _*)
          case None    => client.async().zunionstore(destination, keys: _*)
        }
      }
    }.void

  override def zCard(key: K): F[Option[Long]] =
    JRFuture {
      F.delay {
        client.async().zcard(key)
      }
    }.map(x => Option(Long.box(x)))

  override def zCount(key: K, range: ZRange[V])(implicit ev: Numeric[V]): F[Option[Long]] =
    JRFuture {
      F.delay {
        client.async().zcount(key, range.asJavaRange)
      }
    }.map(x => Option(Long.box(x)))

  override def zLexCount(key: K, range: ZRange[V]): F[Option[Long]] =
    JRFuture {
      F.delay {
        client.async().zlexcount(key, Range.create[V](range.start, range.end))
      }
    }.map(x => Option(Long.box(x)))

  override def zRange(key: K, start: Long, stop: Long): F[List[V]] =
    JRFuture {
      F.delay {
        client.async().zrange(key, start, stop)
      }
    }.map(_.asScala.toList)

  override def zRangeByLex(key: K, range: ZRange[V], limit: Option[RangeLimit]): F[List[V]] =
    JRFuture {
      F.delay {
        limit match {
          case Some(x) =>
            client.async().zrangebylex(key, Range.create[V](range.start, range.end), Limit.create(x.offset, x.count))
          case None => client.async().zrangebylex(key, Range.create[V](range.start, range.end))
        }
      }
    }.map(_.asScala.toList)

  override def zRangeByScore(key: K, range: ZRange[V], limit: Option[RangeLimit])(implicit ev: Numeric[V]): F[List[V]] =
    JRFuture {
      F.delay {
        limit match {
          case Some(x) => client.async().zrangebyscore(key, range.asJavaRange, Limit.create(x.offset, x.count))
          case None    => client.async().zrangebyscore(key, range.asJavaRange)
        }
      }
    }.map(_.asScala.toList)

  override def zRangeByScoreWithScores(key: K, range: ZRange[V], limit: Option[RangeLimit])(
      implicit ev: Numeric[V]): F[List[ScoreWithValue[V]]] =
    JRFuture {
      F.delay {
        limit match {
          case Some(x) =>
            client.async().zrangebyscoreWithScores(key, range.asJavaRange, Limit.create(x.offset, x.count))
          case None => client.async().zrangebyscoreWithScores(key, range.asJavaRange)
        }
      }
    }.map(_.asScala.toList.map(_.asScoreWithValues))

  override def zRangeWithScores(key: K, start: Long, stop: Long): F[List[ScoreWithValue[V]]] =
    JRFuture {
      F.delay {
        client.async().zrangeWithScores(key, start, stop)
      }
    }.map(_.asScala.toList.map(_.asScoreWithValues))

  override def zRank(key: K, value: V): F[Option[Long]] =
    JRFuture {
      F.delay {
        client.async().zrank(key, value)
      }
    }.map(x => Option(Long.box(x)))

  override def zRevRange(key: K, start: Long, stop: Long): F[List[V]] =
    JRFuture {
      F.delay {
        client.async().zrevrange(key, start, stop)
      }
    }.map(_.asScala.toList)

  override def zRevRangeByLex(key: K, range: ZRange[V], limit: Option[RangeLimit]): F[List[V]] =
    JRFuture {
      F.delay {
        limit match {
          case Some(x) =>
            client.async().zrevrangebylex(key, Range.create[V](range.start, range.end), Limit.create(x.offset, x.count))
          case None => client.async().zrevrangebylex(key, Range.create[V](range.start, range.end))
        }
      }
    }.map(_.asScala.toList)

  override def zRevRangeByScore(key: K, range: ZRange[V], limit: Option[RangeLimit])(
      implicit ev: Numeric[V]): F[List[V]] =
    JRFuture {
      F.delay {
        limit match {
          case Some(x) => client.async().zrevrangebyscore(key, range.asJavaRange, Limit.create(x.offset, x.count))
          case None    => client.async().zrevrangebyscore(key, range.asJavaRange)
        }
      }
    }.map(_.asScala.toList)

  override def zRevRangeByScoreWithScores(key: K, range: ZRange[V], limit: Option[RangeLimit])(
      implicit ev: Numeric[V]): F[List[ScoreWithValue[V]]] =
    JRFuture {
      F.delay {
        limit match {
          case Some(x) =>
            client.async().zrangebyscoreWithScores(key, range.asJavaRange, Limit.create(x.offset, x.count))
          case None => client.async().zrangebyscoreWithScores(key, range.asJavaRange)
        }
      }
    }.map(_.asScala.toList.map(_.asScoreWithValues))

  override def zRevRangeWithScores(key: K, start: Long, stop: Long): F[List[ScoreWithValue[V]]] =
    JRFuture {
      F.delay {
        client.async().zrangeWithScores(key, start, stop)
      }
    }.map(_.asScala.toList.map(_.asScoreWithValues))

  override def zRevRank(key: K, value: V): F[Option[Long]] =
    JRFuture {
      F.delay {
        client.async().zrevrank(key, value)
      }
    }.map(x => Option(Long.box(x)))

  override def zScore(key: K, value: V): F[Option[Double]] =
    JRFuture {
      F.delay {
        client.async().zscore(key, value)
      }
    }.map(x => Option(Double.box(x)))

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
