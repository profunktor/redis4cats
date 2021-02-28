/*
 * Copyright 2018-2020 ProfunKtor
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
package pubsub

import cats.effect._
import cats.effect.Ref
import cats.syntax.all._
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.effect.{ JRFuture, Log }
import dev.profunktor.redis4cats.effect.JRFuture.mkEc
import fs2.Stream
import fs2.concurrent.Topic
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import scala.concurrent.ExecutionContext

object PubSub {

  private[redis4cats] def acquireAndRelease[F[_]: Async: Log, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V],
      ec: ExecutionContext
  ): (F[StatefulRedisPubSubConnection[K, V]], StatefulRedisPubSubConnection[K, V] => F[Unit]) = {

    val acquire: F[StatefulRedisPubSubConnection[K, V]] = JRFuture.fromConnectionFuture(
      F.delay(client.underlying.connectPubSubAsync(codec.underlying, client.uri.underlying))
    )(ec)

    val release: StatefulRedisPubSubConnection[K, V] => F[Unit] = c =>
      JRFuture.fromCompletableFuture(F.delay(c.closeAsync()))(ec) *>
          F.info(s"Releasing PubSub connection: ${client.uri.underlying}")

    (acquire, release)
  }

  /**
    * Creates a PubSub Connection.
    *
    * Use this option whenever you need one or more subscribers or subscribers and publishers / stats.
    * */
  def mkPubSubConnection[F[_]: Async: Log, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V]
  ): Resource[F, PubSubCommands[Stream[F, *], K, V]] = mkEc.flatMap { ec =>
    val (acquire, release) = acquireAndRelease[F, K, V](client, codec, ec)
    // One exclusive connection for subscriptions and another connection for publishing / stats
    for {
      state <- Resource.eval(Ref.of[F, Map[K, Topic[F, Option[V]]]](Map.empty))
      sConn <- Resource.make(acquire)(release)
      pConn <- Resource.make(acquire)(release)
    } yield new LivePubSubCommands[F, K, V](state, sConn, pConn, ec)
  }

  /**
    * Creates a PubSub connection.
    *
    * Use this option when you only need to publish and/or get stats such as number of subscriptions.
    * */
  def mkPublisherConnection[F[_]: Async: Log, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V]
  ): Resource[F, PublishCommands[Stream[F, *], K, V]] = mkEc.flatMap { ec =>
    val (acquire, release) = acquireAndRelease[F, K, V](client, codec, ec)
    Resource.make(acquire)(release).map(new Publisher[F, K, V](_, ec))
  }

  /**
    * Creates a PubSub connection.
    *
    * Use this option when you only need to one or more subscribers but no publishing and / or stats.
    * */
  def mkSubscriberConnection[F[_]: Async: Log, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V]
  ): Resource[F, SubscribeCommands[Stream[F, *], K, V]] = mkEc.flatMap { ec =>
    val (acquire, release) = acquireAndRelease[F, K, V](client, codec, ec)
    for {
      state <- Resource.eval(Ref.of[F, Map[K, Topic[F, Option[V]]]](Map.empty))
      conn <- Resource.make(acquire)(release)
    } yield new Subscriber(state, conn, ec)
  }

}
