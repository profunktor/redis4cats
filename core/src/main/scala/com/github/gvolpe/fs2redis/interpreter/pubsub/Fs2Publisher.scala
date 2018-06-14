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
import cats.syntax.functor._
import com.github.gvolpe.fs2redis.algebra.{PubSubStats, PublishCommands}
import com.github.gvolpe.fs2redis.model
import com.github.gvolpe.fs2redis.model.{Fs2RedisChannel, Subscription}
import com.github.gvolpe.fs2redis.util.JRFuture
import fs2.Stream
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

class Fs2Publisher[F[_], K, V](pubConnection: StatefulRedisPubSubConnection[K, V])(implicit F: ConcurrentEffect[F])
    extends PublishCommands[Stream[F, ?], K, V] {

  private[fs2redis] val pubSubStats: PubSubStats[Stream[F, ?], K] = new Fs2PubSubStats(pubConnection)

  override def publish(channel: model.Fs2RedisChannel[K]): Stream[F, V] => Stream[F, Unit] =
    _.evalMap { message =>
      JRFuture { F.delay(pubConnection.async().publish(channel.value, message)) }.void
    }

  override def pubSubChannels: Stream[F, List[K]] =
    pubSubStats.pubSubChannels

  override def pubSubSubscriptions(channel: Fs2RedisChannel[K]): Stream[F, Subscription[K]] =
    pubSubStats.pubSubSubscriptions(channel)

  override def pubSubSubscriptions(channels: List[Fs2RedisChannel[K]]): Stream[F, List[model.Subscription[K]]] =
    pubSubStats.pubSubSubscriptions(channels)

}
