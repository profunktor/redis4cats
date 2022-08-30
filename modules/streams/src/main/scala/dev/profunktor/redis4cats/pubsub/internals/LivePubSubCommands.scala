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
    state: Ref[F, PubSubState[F, K, V]],
    subConnection: StatefulRedisPubSubConnection[K, V],
    pubConnection: StatefulRedisPubSubConnection[K, V]
) extends PubSubCommands[Stream[F, *], K, V] {

  private[redis4cats] val subCommands: SubscribeCommands[Stream[F, *], K, V] =
    new Subscriber[F, K, V](state, subConnection)
  private[redis4cats] val pubSubStats: PubSubStats[Stream[F, *], K] = new LivePubSubStats(pubConnection)

  override def subscribe(channel: RedisChannel[K]): Stream[F, V] =
    subCommands.subscribe(channel)

  override def unsubscribe(channel: RedisChannel[K]): Stream[F, Unit] =
    subCommands.unsubscribe(channel)

  override def psubscribe(pattern: RedisPattern[K]): Stream[F, RedisPatternEvent[K, V]] =
    subCommands.psubscribe(pattern)

  override def punsubscribe(pattern: RedisPattern[K]): Stream[F, Unit] =
    subCommands.punsubscribe(pattern)

  override def publish(channel: RedisChannel[K]): Stream[F, V] => Stream[F, Unit] =
    _.flatMap { message =>
      Stream.resource(
        Resource.eval(state.get) >>= PubSubInternals.channel[F, K, V](state, subConnection).apply(channel)
      ) >>
        Stream.eval(FutureLift[F].lift(pubConnection.async().publish(channel.underlying, message)).void)
    }

  override def pubSubChannels: Stream[F, List[K]] =
    pubSubStats.pubSubChannels

  override def pubSubSubscriptions(channel: RedisChannel[K]): Stream[F, Subscription[K]] =
    pubSubStats.pubSubSubscriptions(channel)

  override def pubSubSubscriptions(channels: List[RedisChannel[K]]): Stream[F, List[Subscription[K]]] =
    pubSubStats.pubSubSubscriptions(channels)

}
