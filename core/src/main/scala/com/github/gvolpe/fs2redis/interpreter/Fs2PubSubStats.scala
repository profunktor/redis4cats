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

package com.github.gvolpe.fs2redis.interpreter

import cats.effect.Concurrent
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.github.gvolpe.fs2redis.algebra.PubSubStats
import com.github.gvolpe.fs2redis.model._
import com.github.gvolpe.fs2redis.util.JRFuture
import fs2.Stream
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

import scala.collection.JavaConverters._

class Fs2PubSubStats[F[_], K, V](pubConnection: StatefulRedisPubSubConnection[K, V])(implicit F: Concurrent[F])
    extends PubSubStats[Stream[F, ?], K] {

  override def pubSubChannels: Stream[F, List[K]] =
    Stream
      .eval {
        JRFuture(F.delay(pubConnection.async().pubsubChannels()))
      }
      .map(_.asScala.toList)

  override def pubSubSubscriptions(channel: Fs2RedisChannel[K]): Stream[F, Long] =
    Stream.eval {
      for {
        kv <- JRFuture(F.delay(pubConnection.async().pubsubNumsub(channel.value)))
        rs <- F.delay(kv.asScala.getOrElse(channel.value, 0L).asInstanceOf[Long])
      } yield rs
    }

  override def pubSubSubscriptions(channels: List[Fs2RedisChannel[K]]): Stream[F, List[Subscriptions[K]]] =
    Stream.eval {
      for {
        kv <- JRFuture(F.delay(pubConnection.async().pubsubNumsub(channels.map(_.value): _*)))
        rs <- F.delay(kv.asScala.toList.map { case (k, n) => Subscriptions(DefaultChannel[K](k), n) })
      } yield rs
    }

}
