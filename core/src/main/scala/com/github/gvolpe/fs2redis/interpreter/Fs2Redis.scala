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

import cats.effect.{Concurrent, Resource}
import cats.syntax.apply._
import cats.syntax.functor._
import com.github.gvolpe.fs2redis.algebra.BasicCommands
import com.github.gvolpe.fs2redis.model.{Fs2RedisClient, Fs2RedisCodec}
import com.github.gvolpe.fs2redis.util.{JRFuture, Log}
import fs2.Stream
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection

import scala.concurrent.duration.FiniteDuration

object Fs2Redis {

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
                                         uri: RedisURI): Resource[F, BasicCommands[F, K, V]] = {
    val (acquire, release) = acquireAndRelease(client, codec, uri)
    Resource.make(acquire)(release).map(_.asInstanceOf[BasicCommands[F, K, V]])
  }

  def stream[F[_]: Concurrent: Log, K, V](client: Fs2RedisClient,
                                          codec: Fs2RedisCodec[K, V],
                                          uri: RedisURI): Stream[F, BasicCommands[F, K, V]] = {
    val (acquire, release) = acquireAndRelease(client, codec, uri)
    Stream.bracket(acquire)(release)
  }

}

private[fs2redis] class Fs2Redis[F[_], K, V](val client: StatefulRedisConnection[K, V])(implicit F: Concurrent[F])
    extends BasicCommands[F, K, V] {

  override def get(k: K): F[Option[V]] =
    JRFuture {
      F.delay(client.async().get(k))
    }.map(Option.apply)

  override def set(k: K, v: V): F[Unit] =
    JRFuture {
      F.delay(client.async().set(k, v))
    }.void

  override def setnx(k: K, v: V): F[Unit] =
    JRFuture {
      F.delay(client.async().setnx(k, v))
    }.void

  override def del(k: K): F[Unit] =
    JRFuture {
      F.delay(client.async().del(k))
    }.void

  override def setex(k: K, v: V, seconds: FiniteDuration): F[Unit] =
    JRFuture {
      F.delay(client.async().setex(k, seconds.toSeconds, v))
    }.void

  override def expire(k: K, seconds: FiniteDuration): F[Unit] =
    JRFuture {
      F.delay(client.async().expire(k, seconds.toSeconds))
    }.void

}
