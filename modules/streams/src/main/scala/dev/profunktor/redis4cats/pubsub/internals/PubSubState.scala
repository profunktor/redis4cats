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

package dev.profunktor.redis4cats.pubsub.internals

import cats.syntax.all._
import cats.effect.kernel.Concurrent
import cats.effect.std.AtomicCell
import dev.profunktor.redis4cats.data.{ RedisChannel, RedisPattern, RedisPatternEvent }

/** We use `AtomicCell` instead of `Ref` because we need locking while side-effecting. */
case class PubSubState[F[_], K, V](
    channelSubs: AtomicCell[F, Map[RedisChannel[K], Redis4CatsSubscription[F, V]]],
    patternSubs: AtomicCell[F, Map[RedisPattern[K], Redis4CatsSubscription[F, RedisPatternEvent[K, V]]]]
)
object PubSubState {
  def make[F[_]: Concurrent, K, V]: F[PubSubState[F, K, V]] =
    for {
      channelSubs <- AtomicCell[F].of(Map.empty[RedisChannel[K], Redis4CatsSubscription[F, V]])
      patternSubs <- AtomicCell[F].of(Map.empty[RedisPattern[K], Redis4CatsSubscription[F, RedisPatternEvent[K, V]]])
    } yield apply(channelSubs, patternSubs)

}
