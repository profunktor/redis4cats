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
  def numPat: F[Long]
  def numSub: F[List[Subscription[K]]]
  def pubSubChannels: F[List[RedisChannel[K]]]
  def pubSubShardChannels: F[List[RedisChannel[K]]]
  def pubSubSubscriptions(channel: RedisChannel[K]): F[Option[Subscription[K]]]
  def pubSubSubscriptions(channels: List[RedisChannel[K]]): F[List[Subscription[K]]]
  def shardNumSub(channels: List[RedisChannel[K]]): F[List[Subscription[K]]]
}

/**
  * @tparam F  the effect type
  * @tparam S  the stream type
  * @tparam K  the channel key type
  * @tparam V  the value type
  */
trait PublishCommands[F[_], S[_], K, V] extends PubSubStats[F, K] {
  def publish(channel: RedisChannel[K]): S[V] => S[Unit]
  def publish(channel: RedisChannel[K], value: V): F[Unit]
}

/**
  * @tparam F  the effect type
  * @tparam S  the stream type
  * @tparam K  the channel key type
  * @tparam V  the value type
  */
trait SubscribeCommands[F[_], S[_], K, V] {
  def subscribe(channel: RedisChannel[K]): S[V]
  def unsubscribe(channel: RedisChannel[K]): F[Unit]
  def psubscribe(channel: RedisPattern[K]): S[RedisPatternEvent[K, V]]
  def punsubscribe(channel: RedisPattern[K]): F[Unit]
}

/**
  * @tparam F  the effect type
  * @tparam S  the stream type
  * @tparam K  the channel key type
  * @tparam V  the value type
  */
trait PubSubCommands[F[_], S[_], K, V] extends PublishCommands[F, S, K, V] with SubscribeCommands[F, S, K, V]
