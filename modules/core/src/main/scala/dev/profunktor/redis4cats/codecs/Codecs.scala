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

package dev.profunktor.redis4cats.codecs

import dev.profunktor.redis4cats.codecs.splits.SplitEpi
import dev.profunktor.redis4cats.data.RedisCodec
import io.lettuce.core.codec.{ RedisCodec => JRedisCodec }
import java.nio.ByteBuffer

object Codecs {

  /**
    * Given a base RedisCodec[K, V1] and a split epimorphism between V1 and V2,
    * a new RedisCodec[K, V2] can be derived.
    * */
  def derive[K, V1, V2](
      baseCodec: RedisCodec[K, V1],
      epi: SplitEpi[V1, V2]
  ): RedisCodec[K, V2] = {
    val codec = baseCodec.underlying
    RedisCodec(
      new JRedisCodec[K, V2] {
        override def decodeKey(bytes: ByteBuffer): K    = codec.decodeKey(bytes)
        override def encodeKey(key: K): ByteBuffer      = codec.encodeKey(key)
        override def encodeValue(value: V2): ByteBuffer = codec.encodeValue(epi.reverseGet(value))
        override def decodeValue(bytes: ByteBuffer): V2 = epi.get(codec.decodeValue(bytes))
      }
    )
  }

  /**
    * Given a base RedisCodec[K1, V1], a split epimorphism between K1 and K2, and
    * a split epimorphism between V1 and V2, a new RedisCodec[K2, V2] can be derived.
    * */
  def derive[K1, K2, V1, V2](
      baseCodec: RedisCodec[K1, V1],
      epiKeys: SplitEpi[K1, K2],
      epiValues: SplitEpi[V1, V2]
  ): RedisCodec[K2, V2] = {
    val codec = baseCodec.underlying
    RedisCodec(
      new JRedisCodec[K2, V2] {
        override def decodeKey(bytes: ByteBuffer): K2   = epiKeys.get(codec.decodeKey(bytes))
        override def encodeKey(key: K2): ByteBuffer     = codec.encodeKey(epiKeys.reverseGet(key))
        override def encodeValue(value: V2): ByteBuffer = codec.encodeValue(epiValues.reverseGet(value))
        override def decodeValue(bytes: ByteBuffer): V2 = epiValues.get(codec.decodeValue(bytes))
      }
    )
  }

}
