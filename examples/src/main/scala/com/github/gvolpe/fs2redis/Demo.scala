/*
 * Copyright 2018-2019 Fs2 Redis
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

import cats.effect.IO
import com.github.gvolpe.fs2redis.domain.{ DefaultRedisCodec, Fs2RedisCodec }
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.{ RedisCodec, StringCodec, ToByteBufEncoder }
import io.netty.buffer.ByteBuf

object Demo {

  val redisURI: RedisURI                         = RedisURI.create("redis://localhost")
  val redisClusterURI: RedisURI                  = RedisURI.create("redis://localhost:30001")
  val stringCodec: Fs2RedisCodec[String, String] = DefaultRedisCodec(StringCodec.UTF8)
  val longCodec: Fs2RedisCodec[String, Long]     = DefaultRedisCodec(LongCodec)

  def putStrLn(str: String): IO[Unit] = IO(println(str))

}

object LongCodec extends RedisCodec[String, Long] with ToByteBufEncoder[String, Long] {

  import java.nio.ByteBuffer

  private val codec = StringCodec.UTF8

  override def decodeKey(bytes: ByteBuffer): String            = codec.decodeKey(bytes)
  override def encodeKey(key: String): ByteBuffer              = codec.encodeKey(key)
  override def encodeValue(value: Long): ByteBuffer            = codec.encodeValue(value.toString)
  override def decodeValue(bytes: ByteBuffer): Long            = codec.decodeValue(bytes).toLong
  override def encodeKey(key: String, target: ByteBuf): Unit   = codec.encodeKey(key, target)
  override def encodeValue(value: Long, target: ByteBuf): Unit = codec.encodeValue(value.toString, target)
  override def estimateSize(keyOrValue: scala.Any): Int        = codec.estimateSize(keyOrValue)
}
