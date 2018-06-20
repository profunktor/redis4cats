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
import com.github.gvolpe.fs2redis.algebra.{HashCommands, StringCommands}
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis.RedisCommands
import com.github.gvolpe.fs2redis.model.{Fs2RedisClient, Fs2RedisCodec}
import com.github.gvolpe.fs2redis.util.{JRFuture, Log}
import fs2.Stream
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection

import scala.concurrent.duration.FiniteDuration

object Fs2Redis {

  trait RedisCommands[F[_], K, V] extends StringCommands[F, K, V] with HashCommands[F, K, V]

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
    extends RedisCommands[F, K, V] {

  import scala.collection.JavaConverters._

  override def del(key: K): F[Unit] =
    JRFuture {
      F.delay(client.async().del(key))
    }.void

  override def expire(key: K, expiresIn: FiniteDuration): F[Unit] =
    JRFuture {
      F.delay(client.async().expire(key, expiresIn.toSeconds))
    }.void

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
    }.map(_.asScala.toList.map(kv => kv.getKey -> kv.getValue).toMap)

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

  override def bitOpAnd(destination: K, source: List[K]): F[Unit] =
    JRFuture {
      F.delay(client.async().bitopAnd(destination, source: _*))
    }.void

  override def bitOpNot(destination: K, source: K): F[Unit] =
    JRFuture {
      F.delay(client.async().bitopNot(destination, source))
    }.void

  override def bitOpOr(destination: K, source: List[K]): F[Unit] =
    JRFuture {
      F.delay(client.async().bitopOr(destination, source: _*))
    }.void

  override def bitOpXor(destination: K, source: List[K]): F[Unit] =
    JRFuture {
      F.delay(client.async().bitopXor(destination, source: _*))
    }.void

  override def hDel(key: K, fields: List[K]): F[Unit] =
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

  override def hmGet(key: K, fields: List[K]): F[Map[K, V]] =
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

}
