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
import cats.effect.std.Dispatcher
import cats.syntax.all._
import dev.profunktor.redis4cats.data.{ RedisChannel, RedisPattern, RedisPatternEvent }
import dev.profunktor.redis4cats.effect.{ FutureLift, Log }
import fs2.Stream
import fs2.concurrent.Topic
import io.lettuce.core.pubsub.{ RedisPubSubListener, StatefulRedisPubSubConnection }

private[pubsub] class Subscriber[F[_]: Async: FutureLift: Log, K, V](
    state: PubSubState[F, K, V],
    subConnection: StatefulRedisPubSubConnection[K, V]
) extends SubscribeCommands[F, Stream[F, *], K, V] {

  override def subscribe(channel: RedisChannel[K]): Stream[F, V] =
    Subscriber.subscribe(
      channel,
      state.channelSubs,
      subConnection,
      subscribeToRedis = FutureLift[F].lift(subConnection.async().subscribe(channel.underlying)).void,
      unsubscribeFromRedis = FutureLift[F].lift(subConnection.async().unsubscribe(channel.underlying)).void
    )((dispatcher, topic) =>
      PubSubInternals.channelListener(channel, (v: V) => topic.publish1(Some(v)).void, dispatcher)
    )

  override def unsubscribe(channel: RedisChannel[K]): F[Unit] =
    Subscriber.unsubscribeFrom(channel, state.channelSubs)

  override def psubscribe(
      pattern: RedisPattern[K]
  ): Stream[F, RedisPatternEvent[K, V]] =
    Subscriber.subscribe(
      pattern,
      state.patternSubs,
      subConnection,
      subscribeToRedis = FutureLift[F].lift(subConnection.async().psubscribe(pattern.underlying)).void,
      unsubscribeFromRedis = FutureLift[F].lift(subConnection.async().punsubscribe(pattern.underlying)).void
    )((dispatcher, topic) =>
      PubSubInternals
        .patternListener(pattern, (evt: RedisPatternEvent[K, V]) => topic.publish1(Some(evt)).void, dispatcher)
    )

  override def punsubscribe(pattern: RedisPattern[K]): F[Unit] =
    Subscriber.unsubscribeFrom(pattern, state.patternSubs)

  override def internalChannelSubscriptions: F[Map[RedisChannel[K], Long]] =
    state.channelSubs.counts

  override def internalPatternSubscriptions: F[Map[RedisPattern[K], Long]] =
    state.patternSubs.counts
}
object Subscriber {

  private def unsubscribeFrom[F[_], K, V](
      key: K,
      state: PubSubState.SubscriptionMap[F, K, V]
  ): F[Unit] =
    state.unsubscribe(key)

  private def subscribe[F[_]: Async: Log, TypedKey, SubValue, K, V](
      key: TypedKey,
      state: PubSubState.SubscriptionMap[F, TypedKey, SubValue],
      subConnection: StatefulRedisPubSubConnection[K, V],
      subscribeToRedis: F[Unit],
      unsubscribeFromRedis: F[Unit]
  )(makeListener: (Dispatcher[F], Topic[F, Option[SubValue]]) => RedisPubSubListener[K, V]): Stream[F, SubValue] =
    state.subscribe(key) {
      for {
        _ <- Resource.eval(Log[F].info(s"Creating subscription for $key"))
        // We use parallel dispatcher because multiple subscribers can be interested in the same key
        dispatcher <- Dispatcher.parallel[F]
        topic <- Resource.eval(Topic[F, Option[SubValue]])
        _ <- Resource.make {
               val listener = makeListener(dispatcher, topic)
               Sync[F].delay(subConnection.addListener(listener)).as(listener)
             }(listener => Sync[F].delay(subConnection.removeListener(listener)))
        _ <- Resource.make(subscribeToRedis)(_ => unsubscribeFromRedis)
        _ <- Resource.eval(Log[F].debug(s"Created subscription for $key"))
      } yield topic
    }
}
