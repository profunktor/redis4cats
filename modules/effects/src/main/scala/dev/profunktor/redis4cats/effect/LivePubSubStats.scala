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

package dev.profunktor.redis4cats.effect

import cats.Functor
import cats.syntax.all._
import dev.profunktor.redis4cats.algebra.PubSubStats
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.JavaConversions._
import io.lettuce.core.api.async.RedisAsyncCommands

import java.{ util => ju }
import java.lang.{ Long => JLong }

private[redis4cats] class LivePubSubStats[F[_]: Functor: FutureLift, K, V](
    async: RedisAsyncCommands[K, V]
) extends PubSubStats[F, K] {

  override def numPat: F[Long] =
    FutureLift[F].lift(async.pubsubNumpat()).map(Long.unbox)

  override def numSub: F[List[Subscription[K]]] =
    FutureLift[F]
      .lift(async.pubsubNumsub())
      .map(LivePubSubStats.toSubscription[K])

  override def pubSubChannels: F[List[RedisChannel[K]]] =
    FutureLift[F]
      .lift(async.pubsubChannels())
      .map(_.asScala.toList.map(RedisChannel[K]))

  override def pubSubShardChannels: F[List[RedisChannel[K]]] =
    FutureLift[F]
      .lift(async.pubsubShardChannels())
      .map(_.asScala.toList.map(RedisChannel[K]))

  override def pubSubSubscriptions(channel: RedisChannel[K]): F[Option[Subscription[K]]] =
    pubSubSubscriptions(List(channel)).map(_.headOption)

  override def pubSubSubscriptions(channels: List[RedisChannel[K]]): F[List[Subscription[K]]] =
    FutureLift[F]
      .lift(async.pubsubNumsub(channels.map(_.underlying): _*))
      .map(LivePubSubStats.toSubscription[K])

  override def shardNumSub(channels: List[RedisChannel[K]]): F[List[Subscription[K]]] =
    FutureLift[F]
      .lift(async.pubsubShardNumsub(channels.map(_.underlying): _*))
      .map(LivePubSubStats.toSubscription[K])
}

private[redis4cats] object LivePubSubStats {
  private def toSubscription[K](map: ju.Map[K, JLong]): List[Subscription[K]] =
    map.asScala.toList.map { case (k, n) => Subscription(RedisChannel[K](k), Long.unbox(n)) }
}
