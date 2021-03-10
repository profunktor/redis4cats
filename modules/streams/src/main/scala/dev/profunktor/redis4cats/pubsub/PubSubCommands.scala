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

import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.pubsub.data.Subscription

trait PubSubStats[F[_], K] {
  def pubSubChannels: F[List[K]]
  def pubSubSubscriptions(channel: RedisChannel[K]): F[Subscription[K]]
  def pubSubSubscriptions(channels: List[RedisChannel[K]]): F[List[Subscription[K]]]
}

trait PublishCommands[F[_], K, V] extends PubSubStats[F, K] {
  def publish(channel: RedisChannel[K]): F[V] => F[Unit]
}

trait SubscribeCommands[F[_], K, V] {
  def subscribe(channel: RedisChannel[K]): F[V]
  def unsubscribe(channel: RedisChannel[K]): F[Unit]
}

trait PubSubCommands[F[_], K, V] extends PublishCommands[F, K, V] with SubscribeCommands[F, K, V]
