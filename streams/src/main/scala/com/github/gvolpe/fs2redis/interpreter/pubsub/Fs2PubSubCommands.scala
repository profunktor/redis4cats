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

import cats.effect.{ ConcurrentEffect, Sync }
import cats.effect.concurrent.Ref
import cats.syntax.all._
import com.github.gvolpe.fs2redis.algebra.{ PubSubCommands, PubSubStats, SubscribeCommands }
import com.github.gvolpe.fs2redis.domain.Fs2RedisChannel
import com.github.gvolpe.fs2redis.interpreter.pubsub.internals.{ Fs2PubSubInternals, PubSubState }
import com.github.gvolpe.fs2redis.streams.Subscription
import com.github.gvolpe.fs2redis.util.JRFuture
import fs2.Stream
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

class Fs2PubSubCommands[F[_]: ConcurrentEffect, K, V](
    state: Ref[F, PubSubState[F, K, V]],
    subConnection: StatefulRedisPubSubConnection[K, V],
    pubConnection: StatefulRedisPubSubConnection[K, V]
) extends PubSubCommands[Stream[F, ?], K, V] {

  private[fs2redis] val subCommands: SubscribeCommands[Stream[F, ?], K, V] =
    new Fs2Subscriber[F, K, V](state, subConnection)
  private[fs2redis] val pubSubStats: PubSubStats[Stream[F, ?], K] = new Fs2PubSubStats(pubConnection)

  override def subscribe(channel: Fs2RedisChannel[K]): Stream[F, V] =
    subCommands.subscribe(channel)

  override def unsubscribe(channel: Fs2RedisChannel[K]): Stream[F, Unit] =
    subCommands.unsubscribe(channel)

  override def publish(channel: Fs2RedisChannel[K]): Stream[F, V] => Stream[F, Unit] =
    _.evalMap { message =>
      val getOrCreateTopicListener = Fs2PubSubInternals[F, K, V](state, subConnection)
      for {
        st <- state.get
        _ <- getOrCreateTopicListener(channel)(st)
        _ <- JRFuture { Sync[F].delay(pubConnection.async().publish(channel.value, message)) }
      } yield ()
    }

  override def pubSubChannels: Stream[F, List[K]] =
    pubSubStats.pubSubChannels

  override def pubSubSubscriptions(channel: Fs2RedisChannel[K]): Stream[F, Subscription[K]] =
    pubSubStats.pubSubSubscriptions(channel)

  override def pubSubSubscriptions(channels: List[Fs2RedisChannel[K]]): Stream[F, List[Subscription[K]]] =
    pubSubStats.pubSubSubscriptions(channels)

}
