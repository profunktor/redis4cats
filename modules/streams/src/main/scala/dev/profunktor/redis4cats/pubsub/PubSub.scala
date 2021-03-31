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
package pubsub

import cats.{ Apply, FlatMap }
import cats.effect._
import cats.effect.Ref
import cats.syntax.all._
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.effect._
import dev.profunktor.redis4cats.pubsub.internals.{ LivePubSubCommands, Publisher, Subscriber }
import fs2.Stream
import fs2.concurrent.Topic
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

object PubSub {

  private[redis4cats] def acquireAndRelease[F[_]: Apply: FutureLift: Log: RedisExecutor, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V]
  ): (F[StatefulRedisPubSubConnection[K, V]], StatefulRedisPubSubConnection[K, V] => F[Unit]) = {

    val acquire: F[StatefulRedisPubSubConnection[K, V]] = FutureLift[F].liftConnectionFuture(
      RedisExecutor[F].lift(client.underlying.connectPubSubAsync(codec.underlying, client.uri.underlying))
    )

    val release: StatefulRedisPubSubConnection[K, V] => F[Unit] = c =>
      FutureLift[F].liftCompletableFuture(RedisExecutor[F].lift(c.closeAsync())) *>
          Log[F].info(s"Releasing PubSub connection: ${client.uri.underlying}")

    (acquire, release)
  }

  /**
    * Creates a PubSub Connection.
    *
    * Use this option whenever you need one or more subscribers or subscribers and publishers / stats.
    * */
  def mkPubSubConnection[F[_]: Async: FutureLift: Log: RedisMonad, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V]
  ): Resource[F, PubSubCommands[Stream[F, *], K, V]] =
    RedisMonad[F].newExecutor.flatMap { implicit ec =>
      val (acquire, release) = acquireAndRelease[F, K, V](client, codec)
      // One exclusive connection for subscriptions and another connection for publishing / stats
      for {
        state <- Resource.eval(Ref.of[F, Map[K, Topic[F, Option[V]]]](Map.empty))
        sConn <- Resource.make(acquire)(release)
        pConn <- Resource.make(acquire)(release)
      } yield new LivePubSubCommands[F, K, V](state, sConn, pConn)
    }

  /**
    * Creates a PubSub connection.
    *
    * Use this option when you only need to publish and/or get stats such as number of subscriptions.
    * */
  def mkPublisherConnection[F[_]: FlatMap: FutureLift: Log: RedisMonad, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V]
  ): Resource[F, PublishCommands[Stream[F, *], K, V]] =
    RedisMonad[F].newExecutor.flatMap { implicit redisExecutor =>
      val (acquire, release) = acquireAndRelease[F, K, V](client, codec)
      Resource.make(acquire)(release).map(new Publisher[F, K, V](_))
    }

  /**
    * Creates a PubSub connection.
    *
    * Use this option when you only need to one or more subscribers but no publishing and / or stats.
    * */
  def mkSubscriberConnection[F[_]: Async: FutureLift: Log, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V]
  ): Resource[F, SubscribeCommands[Stream[F, *], K, V]] =
    RedisMonad[F].newExecutor.flatMap { implicit ec =>
      val (acquire, release) = acquireAndRelease[F, K, V](client, codec)
      for {
        state <- Resource.eval(Ref.of[F, Map[K, Topic[F, Option[V]]]](Map.empty))
        conn <- Resource.make(acquire)(release)
      } yield new Subscriber(state, conn)
    }

}
