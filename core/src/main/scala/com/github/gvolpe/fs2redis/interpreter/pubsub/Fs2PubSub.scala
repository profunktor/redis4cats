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

package com.github.gvolpe.fs2redis.interpreter.pubsub

import cats.effect.ConcurrentEffect
import cats.effect.concurrent.Ref
import cats.syntax.all._
import com.github.gvolpe.fs2redis.algebra.{PubSubCommands, PublishCommands}
import com.github.gvolpe.fs2redis.model._
import com.github.gvolpe.fs2redis.util.JRFuture
import fs2.Stream
import fs2.async.mutable
import io.lettuce.core.RedisURI
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

object Fs2PubSub {

  private[fs2redis] def acquireAndRelease[F[_], K, V](client: Fs2RedisClient,
                                                      codec: Fs2RedisCodec[K, V],
                                                      uri: RedisURI)(implicit F: ConcurrentEffect[F])
    : (F[StatefulRedisPubSubConnection[K, V]], StatefulRedisPubSubConnection[K, V] => F[Unit]) = {

    val acquire: F[StatefulRedisPubSubConnection[K, V]] = JRFuture.fromConnectionFuture {
      F.delay(client.underlying.connectPubSubAsync(codec.underlying, uri))
    }

    val release: StatefulRedisPubSubConnection[K, V] => F[Unit] = c =>
      JRFuture.fromCompletableFuture(F.delay(c.closeAsync())) *>
        F.delay(s"Releasing PubSub connection: ${client.underlying}")

    (acquire, release)
  }

  /**
    * Creates a PubSub Connection.
    *
    * Use this option whenever you need one or more subscribers or subscribers and publishers.
    * */
  def mkPubSubConnection[F[_]: ConcurrentEffect, K, V](client: Fs2RedisClient,
                                                       codec: Fs2RedisCodec[K, V],
                                                       uri: RedisURI): Stream[F, PubSubCommands[Stream[F, ?], K, V]] = {
    val (acquire, release) = acquireAndRelease[F, K, V](client, codec, uri)
    // One exclusive connection for subscriptions and another connection for publishing / stats
    for {
      state <- Stream.eval(Ref.of(Map.empty[K, mutable.Topic[F, Option[V]]]))
      sConn <- Stream.bracket(acquire)(release)
      pConn <- Stream.bracket(acquire)(release)
      subs  <- Stream.emit(new Fs2PubSubCommands[F, K, V](state, sConn, pConn))
    } yield subs

  }

  /**
    * Creates a PubSub connection.
    *
    * Use this option when you only need to publish and/or get PubSun stats such as number of subscriptions.
    * */
  def mkPublisherConnection[F[_]: ConcurrentEffect, K, V](
      client: Fs2RedisClient,
      codec: Fs2RedisCodec[K, V],
      uri: RedisURI): Stream[F, PublishCommands[Stream[F, ?], K, V]] = {

    val (acquire, release) = acquireAndRelease[F, K, V](client, codec, uri)
    Stream.bracket(acquire)(release).map(c => new Fs2Publisher[F, K, V](c))
  }

}
