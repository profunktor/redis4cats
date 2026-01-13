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

package dev.profunktor.redis4cats.algebra

import dev.profunktor.redis4cats.data.{ RedisChannel, Subscription }

trait Publish[F[_], K, V] {

  /** Publishes a message to the given channel.
    *
    * @param channel
    *   the Redis channel to publish to
    * @param message
    *   the message value to publish
    * @return
    *   the number of clients that received the message
    */
  def publish(channel: RedisChannel[K], message: V): F[Long]

  /** Publishes a message to the given shard channel.
    *
    * @param channel
    *   the Redis shard channel to publish to
    * @param message
    *   the message value to publish
    * @return
    *   the number of clients that received the message
    */
  def spublish(channel: RedisChannel[K], message: V): F[Long]
}

trait PubSubStats[F[_], K] {

  /** Returns the total number of pattern subscriptions across all clients.
    *
    * @return
    *   the number of active pattern subscriptions
    */
  def numPat: F[Long]

  /** Returns the number of subscribers for all channels.
    *
    * @return
    *   list of subscriptions for all channels
    */
  def numSub: F[List[Subscription[K]]]

  /** Lists all currently active channels.
    *
    * @return
    *   list of active channels
    */
  def pubSubChannels: F[List[RedisChannel[K]]]

  /** Lists all currently active shard channels.
    *
    * @return
    *   list of active shard channels
    */
  def pubSubShardChannels: F[List[RedisChannel[K]]]

  /** Returns the subscription information for a specific channel.
    *
    * @param channel
    *   the channel to query
    * @return
    *   subscription information if the channel exists
    */
  def pubSubSubscriptions(channel: RedisChannel[K]): F[Option[Subscription[K]]]

  /** Returns the subscription information for the specified channels.
    *
    * @param channels
    *   the channels to query
    * @return
    *   list of subscriptions for the specified channels
    */
  def pubSubSubscriptions(channels: List[RedisChannel[K]]): F[List[Subscription[K]]]

  /** Returns the number of subscribers for the specified shard channels.
    *
    * @param channels
    *   the shard channels to query
    * @return
    *   list of subscriptions for the specified shard channels
    */
  def shardNumSub(channels: List[RedisChannel[K]]): F[List[Subscription[K]]]
}

/** Combines publishing and pub/sub statistics commands.
  *
  * Note: This trait does NOT include subscribe functionality. For full pub/sub with subscriptions, see the streams
  * module's PubSubCommands.
  */
trait PublishAndStatsCommands[F[_], K, V] extends Publish[F, K, V] with PubSubStats[F, K]
