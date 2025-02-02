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

import cats.Applicative
import cats.effect.kernel._
import cats.effect.kernel.implicits._
import cats.syntax.all._
import dev.profunktor.redis4cats.data.RedisChannel
import dev.profunktor.redis4cats.data.RedisPattern
import dev.profunktor.redis4cats.data.RedisPatternEvent
import dev.profunktor.redis4cats.effect.{ FutureLift, Log }
import fs2.Stream
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

private[pubsub] class Subscriber[F[_]: Async: FutureLift: Log, K, V](
    state: Ref[F, PubSubState[F, K, V]],
    subConnection: StatefulRedisPubSubConnection[K, V]
) extends SubscribeCommands[F, Stream[F, *], K, V] {

  override def subscribe(channel: RedisChannel[K]): Stream[F, V] =
    Stream
      .resource(Resource.eval(state.get) >>= PubSubInternals.channel[F, K, V](state, subConnection).apply(channel))
      .evalTap(_ => FutureLift[F].lift(subConnection.async().subscribe(channel.underlying)))
      .flatMap(_.subscribe(500).unNone)

  override def unsubscribe(channel: RedisChannel[K]): F[Unit] =
    FutureLift[F]
      .lift(subConnection.async().unsubscribe(channel.underlying))
      .void
      .guarantee(state.get.flatMap { st =>
        st.channels.get(channel.underlying).fold(Applicative[F].unit)(_.publish1(none[V]).void) *> state
          .update(s => s.copy(channels = s.channels - channel.underlying))
      })

  override def psubscribe(pattern: RedisPattern[K]): Stream[F, RedisPatternEvent[K, V]] =
    Stream
      .resource(Resource.eval(state.get) >>= PubSubInternals.pattern[F, K, V](state, subConnection).apply(pattern))
      .evalTap(_ => FutureLift[F].lift(subConnection.async().psubscribe(pattern.underlying)))
      .flatMap(_.subscribe(500).unNone)

  override def punsubscribe(pattern: RedisPattern[K]): F[Unit] =
    FutureLift[F]
      .lift(subConnection.async().punsubscribe(pattern.underlying))
      .void
      .guarantee(state.get.flatMap { st =>
        st.patterns
          .get(pattern.underlying)
          .fold(Applicative[F].unit)(_.publish1(none[RedisPatternEvent[K, V]]).void) *> state
          .update(s => s.copy(patterns = s.patterns - pattern.underlying))
      })
}
