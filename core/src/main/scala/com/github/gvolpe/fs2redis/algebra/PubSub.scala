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

trait Subscriber[F[_]] {
  def subscribe(channel: Fs2RedisChannel): F[Unit]
  def unsubscribe(channel: Fs2RedisChannel): F[Unit]
}

trait Publisher[F[_]] {
  def publish[A](channel: Fs2RedisChannel, message: A): F[Unit]
}

trait PubSub[F[_]] {
  def pubSubChannels[A]: F[List[A]]
  def pubSubSubscriptions(channel: Fs2RedisChannel): F[Long]
  def pubSubSubscriptions(channels: List[Fs2RedisChannel]): F[List[Subscriptions]]
}

trait PubSubConnection[F[_]] {
  def connect[K, V](codec: Fs2RedisCodec[K, V], uri: RedisURI): F[Fs2RedisPubSubConnection[K, V]]
}
