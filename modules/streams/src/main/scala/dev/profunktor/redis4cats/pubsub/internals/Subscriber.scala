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

import cats.Applicative
import cats.effect.kernel._
import cats.effect.kernel.implicits._
import cats.effect.std.{ AtomicCell, Dispatcher }
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
    state.channelSubs.get.map(_.iterator.map { case (k, v) => k -> v.subscribers }.toMap)

  override def internalPatternSubscriptions: F[Map[RedisPattern[K], Long]] =
    state.patternSubs.get.map(_.iterator.map { case (k, v) => k -> v.subscribers }.toMap)
}
object Subscriber {

  /** Check if we have a subscriber for this channel and remove it if we do.
    *
    * If it is the last subscriber, perform the subscription cleanup.
    */
  private def onStreamTermination[F[_]: Applicative: Log, K, V](
      subs: AtomicCell[F, Map[K, Redis4CatsSubscription[F, V]]],
      key: K
  ): F[Unit] = subs.evalUpdate { subscribers =>
    subscribers.get(key) match {
      case None =>
        Log[F]
          .error(
            s"We were notified about stream termination for $key but we don't have a subscription, " +
              s"this is a bug in redis4cats!"
          )
          .as(subscribers)
      case Some(sub) =>
        if (!sub.isLastSubscriber) subscribers.updated(key, sub.removeSubscriber).pure
        else sub.cleanup.as(subscribers - key)
    }
  }

  private def unsubscribeFrom[F[_]: MonadCancelThrow: Log, K, V](
      key: K,
      subs: AtomicCell[F, Map[K, Redis4CatsSubscription[F, V]]]
  ): F[Unit] =
    subs.evalUpdate { subscribers =>
      subscribers.get(key) match {
        case None =>
          // No subscription = nothing to do
          Log[F]
            .debug(s"Not unsubscribing from $key because we don't have a subscription")
            .as(subscribers)
        case Some(sub) =>
          // Publish `None` which will terminate all streams, which will perform cleanup once the last stream
          // terminates.
          (Log[F].info(
            s"Unsubscribing from $key with ${sub.subscribers} subscribers"
          ) *> sub.topic.publish1(None)).uncancelable.as(subscribers)
      }
    }

  private def subscribe[F[_]: Async: Log, TypedKey, SubValue, K, V](
      key: TypedKey,
      subs: AtomicCell[F, Map[TypedKey, Redis4CatsSubscription[F, SubValue]]],
      subConnection: StatefulRedisPubSubConnection[K, V],
      subscribeToRedis: F[Unit],
      unsubscribeFromRedis: F[Unit]
  )(makeListener: (Dispatcher[F], Topic[F, Option[SubValue]]) => RedisPubSubListener[K, V]): Stream[F, SubValue] =
    Stream
      .eval(subs.evalModify { subscribers =>
        def stream(sub: Redis4CatsSubscription[F, SubValue]) =
          sub.stream(onStreamTermination(subs, key))

        subscribers.get(key) match {
          case Some(subscription) =>
            // We have an existing subscription, mark that it has one more subscriber.
            val newSubscription = subscription.addSubscriber
            val newSubscribers  = subscribers.updated(key, newSubscription)
            Log[F]
              .debug(
                s"Returning existing subscription for $key, " +
                  s"subscribers: ${subscription.subscribers} -> ${newSubscription.subscribers}"
              )
              .as((newSubscribers, stream(newSubscription)))

          case None =>
            // No existing subscription, create a new one.
            val makeSubscription = for {
              _ <- Log[F].info(s"Creating subscription for $key")
              // We use parallel dispatcher because multiple subscribers can be interested in the same key
              dispatcherTpl <- Dispatcher.parallel[F].allocated
              (dispatcher, cleanupDispatcher) = dispatcherTpl
              topic <- Topic[F, Option[SubValue]]
              listener        = makeListener(dispatcher, topic)
              cleanupListener = Sync[F].delay(subConnection.removeListener(listener))
              cleanup = (
                          Log[F].debug(s"Cleaning up resources for $key subscription") *>
                            unsubscribeFromRedis *> cleanupListener *> cleanupDispatcher *>
                            Log[F].debug(s"Cleaned up resources for $key subscription")
                        ).uncancelable
              _ <- Sync[F].delay(subConnection.addListener(listener))
              _ <- subscribeToRedis
              sub            = Redis4CatsSubscription(topic, subscribers = 1, cleanup)
              newSubscribers = subscribers.updated(key, sub)
              _ <- Log[F].debug(s"Created subscription for $key")
            } yield (newSubscribers, stream(sub))

            makeSubscription.uncancelable
        }
      })
      .flatten
}
