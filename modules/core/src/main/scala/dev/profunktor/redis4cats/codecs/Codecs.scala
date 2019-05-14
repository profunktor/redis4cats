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

package dev.profunktor.redis4cats.codecs

import dev.profunktor.redis4cats.domain.{ LiveRedisCodec, RedisCodec }
import java.nio.ByteBuffer

import dev.profunktor.redis4cats.codecs.splits.SplitEpi
import io.lettuce.core.codec.{ RedisCodec => JRedisCodec, ToByteBufEncoder }
import io.netty.buffer.ByteBuf

object Codecs {

  /**
    * Given a base `Fs2RedisCodec[K, K]` and evidence of a split epimorphism between K and V
    * a new `Fs2RedisCodec[K, V]` can be derived.
    * */
  def derive[K, V](
      baseCodec: RedisCodec[K, K],
      epi: SplitEpi[K, V]
  ): RedisCodec[K, V] = {
    val codec = baseCodec.underlying
    LiveRedisCodec(
      new JRedisCodec[K, V] with ToByteBufEncoder[K, V] {
        override def decodeKey(bytes: ByteBuffer): K              = codec.decodeKey(bytes)
        override def encodeKey(key: K): ByteBuffer                = codec.encodeKey(key)
        override def encodeValue(value: V): ByteBuffer            = codec.encodeValue(epi.reverseGet(value))
        override def decodeValue(bytes: ByteBuffer): V            = epi.get(codec.decodeValue(bytes))
        override def encodeKey(key: K, target: ByteBuf): Unit     = codec.encodeKey(key, target)
        override def encodeValue(value: V, target: ByteBuf): Unit = codec.encodeValue(epi.reverseGet(value), target)
        override def estimateSize(keyOrValue: scala.Any): Int     = codec.estimateSize(keyOrValue)
      }
    )
  }

}
