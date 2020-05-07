/*
 * Copyright 2018-2020 ProfunKtor
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

import io.lettuce.core.{ ReadFrom => JReadFrom }
import io.lettuce.core.codec.{ RedisCodec => JRedisCodec, StringCodec, ToByteBufEncoder }
import io.lettuce.core.{ KeyScanCursor => JKeyScanCursor }
import dev.profunktor.redis4cats.JavaConversions._

object data {

  final case class RedisChannel[K](underlying: K) extends AnyVal

  type JCodec[K, V] = JRedisCodec[K, V] with ToByteBufEncoder[K, V]

  final case class RedisCodec[K, V](underlying: JCodec[K, V]) extends AnyVal
  final case class NodeId(value: String) extends AnyVal

  final case class KeyScanCursor[K](underlying: JKeyScanCursor[K]) extends AnyVal {
    def keys: List[K]  = underlying.getKeys.asScala.toList
    def cursor: String = underlying.getCursor
  }

  object RedisCodec {
    val Ascii = RedisCodec(StringCodec.ASCII)
    val Utf8  = RedisCodec(StringCodec.UTF8)
  }

  object ReadFrom {
    val Master           = JReadFrom.MASTER
    val MasterPreferred  = JReadFrom.MASTER_PREFERRED
    val Nearest          = JReadFrom.NEAREST
    val Replica          = JReadFrom.REPLICA
    val ReplicaPreferred = JReadFrom.REPLICA_PREFERRED
  }

}
