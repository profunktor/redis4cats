/*
 * Copyright 2018-2019 ProfunKtor
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

import io.lettuce.core.{ RedisClient => JRedisClient, ReadFrom => JReadFrom }
import io.lettuce.core.cluster.{ RedisClusterClient => JClusterClient }
import io.lettuce.core.codec.{ RedisCodec => JRedisCodec, StringCodec, ToByteBufEncoder }
import io.lettuce.core.masterreplica.StatefulRedisMasterReplicaConnection

object domain {

  trait RedisClient {
    def underlying: JRedisClient
  }
  case class LiveRedisClient(underlying: JRedisClient) extends RedisClient

  trait RedisClusterClient {
    def underlying: JClusterClient
  }
  case class LiveRedisClusterClient(underlying: JClusterClient) extends RedisClusterClient

  trait RedisMasterReplicaConnection[K, V] {
    def underlying: StatefulRedisMasterReplicaConnection[K, V]
  }
  case class LiveRedisMasterSlaveConnection[K, V](underlying: StatefulRedisMasterReplicaConnection[K, V])
      extends RedisMasterReplicaConnection[K, V]

  trait RedisChannel[K] {
    def underlying: K
  }
  case class LiveChannel[K](underlying: K) extends RedisChannel[K]

  type JCodec[K, V] = JRedisCodec[K, V] with ToByteBufEncoder[K, V]

  trait RedisCodec[K, V] {
    def underlying: JCodec[K, V]
  }
  case class LiveRedisCodec[K, V](underlying: JCodec[K, V]) extends RedisCodec[K, V]

  case class NodeId(value: String) extends AnyVal

  object RedisCodec {
    val Ascii = LiveRedisCodec(StringCodec.ASCII)
    val Utf8  = LiveRedisCodec(StringCodec.UTF8)
  }

  object ReadFrom {
    val Master           = JReadFrom.MASTER
    val MasterPreferred  = JReadFrom.MASTER_PREFERRED
    val Nearest          = JReadFrom.NEAREST
    val Replica          = JReadFrom.REPLICA
    val ReplicaPreferred = JReadFrom.REPLICA_PREFERRED
  }

}
