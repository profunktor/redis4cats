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

package com.github.gvolpe.fs2redis

import io.lettuce.core.{GeoArgs, RedisClient}
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands

object model {

  trait Fs2RedisClient {
    def underlying: RedisClient
  }
  case class DefaultRedisClient(underlying: RedisClient) extends Fs2RedisClient

  trait Fs2RedisChannel[K] {
    def value: K
  }
  case class DefaultChannel[K](value: K) extends Fs2RedisChannel[K]

  trait Fs2RedisPubSubCommands[K, V] {
    def underlying: RedisPubSubAsyncCommands[K, V]
  }
  case class DefaultPubSubCommands[K, V](underlying: RedisPubSubAsyncCommands[K, V])
      extends Fs2RedisPubSubCommands[K, V]

  trait Fs2RedisCodec[K, V] {
    def underlying: RedisCodec[K, V]
  }
  case class DefaultRedisCodec[K, V](underlying: RedisCodec[K, V]) extends Fs2RedisCodec[K, V]

  case class RedisMessage(value: String) extends AnyVal
  case class Subscription[K](channel: Fs2RedisChannel[K], number: Long)

  object Subscription {
    def empty[K](channel: Fs2RedisChannel[K]): Subscription[K] =
      Subscription[K](channel, 0L)
  }

  final case class StreamingMessage[K, V](key: K, body: Map[K, V])
  final case class StreamingMessageWithId[K, V](id: MessageId, key: K, body: Map[K, V])
  final case class MessageId(value: String) extends AnyVal

  sealed trait StreamingOffset[K] extends Product with Serializable {
    def key: K
    def offset: String
  }

  object StreamingOffset {
    case class All[K](key: K) extends StreamingOffset[K] {
      override def offset: String = "0"
    }
    case class Latest[K](key: K) extends StreamingOffset[K] {
      override def offset: String = "$"
    }
    case class Custom[K](key: K, offset: String) extends StreamingOffset[K]
  }

  case class Distance(value: Double)  extends AnyVal
  case class GeoHash(value: Long)     extends AnyVal
  case class Latitude(value: Double)  extends AnyVal
  case class Longitude(value: Double) extends AnyVal

  case class GeoLocation[V](lon: Longitude, lat: Latitude, value: V)
  case class GeoRadius(lon: Longitude, lat: Latitude, dist: Distance)

  case class GeoCoordinate(x: Double, y: Double)
  case class GeoRadiusResult[V](value: V, dist: Distance, hash: GeoHash, coordinate: GeoCoordinate)
  case class GeoRadiusKeyStorage[K](key: K, count: Long, sort: GeoArgs.Sort)
  case class GeoRadiusDistStorage[K](key: K, count: Long, sort: GeoArgs.Sort)

  case class Score(value: Double) extends AnyVal
  case class ScoreWithValue[V](score: Score, value: V)
  case class ZRange[V](start: V, end: V)
  case class RangeLimit(offset: Long, count: Long)
}
