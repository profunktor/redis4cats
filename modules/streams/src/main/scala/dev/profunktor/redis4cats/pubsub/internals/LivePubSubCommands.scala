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

package dev.profunktor.redis4cats
package pubsub
package internals

import cats.effect.kernel._
import cats.syntax.all._
import dev.profunktor.redis4cats.data.RedisChannel
import dev.profunktor.redis4cats.data.RedisPattern
import dev.profunktor.redis4cats.data.RedisPatternEvent
import dev.profunktor.redis4cats.pubsub.data.Subscription
import dev.profunktor.redis4cats.effect.{ FutureLift, Log }
import fs2.Stream
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

private[pubsub] class LivePubSubCommands[F[_]: Async: Log, K, V](
    state: PubSubState[F, K, V],
    subConnection: StatefulRedisPubSubConnection[K, V],
    pubConnection: StatefulRedisPubSubConnection[K, V]
) extends PubSubCommands[F, Stream[F, *], K, V] {

  private[redis4cats] val subCommands: SubscribeCommands[F, Stream[F, *], K, V] =
    new Subscriber[F, K, V](state, subConnection)
  private[redis4cats] val pubSubStats: PubSubStats[F, K] = new LivePubSubStats(pubConnection)

  override def subscribe(channel: RedisChannel[K]): Stream[F, V] =
    subCommands.subscribe(channel)

  override def unsubscribe(channel: RedisChannel[K]): F[Unit] =
    subCommands.unsubscribe(channel)

  override def psubscribe(pattern: RedisPattern[K]): Stream[F, RedisPatternEvent[K, V]] =
    subCommands.psubscribe(pattern)

  override def punsubscribe(pattern: RedisPattern[K]): F[Unit] =
    subCommands.punsubscribe(pattern)

  override def internalChannelSubscriptions: F[Map[RedisChannel[K], Long]] =
    subCommands.internalChannelSubscriptions

  override def internalPatternSubscriptions: F[Map[RedisPattern[K], Long]] =
    subCommands.internalPatternSubscriptions

  override def publish(channel: RedisChannel[K]): Stream[F, V] => Stream[F, Long] =
    _.evalMap(publish(channel, _))

  override def publish(channel: RedisChannel[K], message: V): F[Long] =
    FutureLift[F].lift(pubConnection.async().publish(channel.underlying, message)).map(l => l: Long)

  override def numPat: F[Long] =
    pubSubStats.numPat

  override def numSub: F[List[Subscription[K]]] =
    pubSubStats.numSub

  override def pubSubChannels: F[List[RedisChannel[K]]] =
    pubSubStats.pubSubChannels

  override def pubSubShardChannels: F[List[RedisChannel[K]]] =
    pubSubStats.pubSubShardChannels

  override def pubSubSubscriptions(channel: RedisChannel[K]): F[Option[Subscription[K]]] =
    pubSubStats.pubSubSubscriptions(channel)

  override def pubSubSubscriptions(channels: List[RedisChannel[K]]): F[List[Subscription[K]]] =
    pubSubStats.pubSubSubscriptions(channels)

  override def shardNumSub(channels: List[RedisChannel[K]]): F[List[Subscription[K]]] =
    pubSubStats.shardNumSub(channels)
}
