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

package com.github.gvolpe.fs2redis.algebra

import com.github.gvolpe.fs2redis.model._
import io.lettuce.core.RedisURI

trait PubSubCommands[F[_], K, V] {
  def subscribe(channel: Fs2RedisChannel[K]): F[V]
  def unsubscribe(channel: Fs2RedisChannel[K]): F[Unit]
  def publish(channel: Fs2RedisChannel[K]): F[V] => F[Unit]
}

trait PubSub[F[_], K] {
  def pubSubChannels: F[List[K]]
  def pubSubSubscriptions(channel: Fs2RedisChannel[K]): F[Long]
  def pubSubSubscriptions(channels: List[Fs2RedisChannel[K]]): F[List[Subscriptions[K]]]
}

trait PubSubConnection[F[_]] {
  def createPubSubConnection[K, V](codec: Fs2RedisCodec[K, V], uri: RedisURI): F[PubSubCommands[F, K, V]]
}
