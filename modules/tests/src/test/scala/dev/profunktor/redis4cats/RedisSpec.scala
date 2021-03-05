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

import cats.syntax.flatMap._
import dev.profunktor.redis4cats.data.RedisCodec
import io.lettuce.core.codec.{ ToByteBufEncoder, RedisCodec => JRedisCodec, StringCodec => JStringCodec }
import io.netty.buffer.ByteBuf

class RedisSpec extends Redis4CatsFunSuite(false) with TestScenarios {

  test("geo api")(withRedis(locationScenario))

  test("hashes api")(withRedis(hashesScenario))

  test("lists api")(withRedis(listsScenario))

  test("keys api")(withRedis(cmd => keysScenario(cmd) >> scanScenario(cmd)))

  test("sets api")(withRedis(setsScenario))

  test("sorted sets api")(withAbstractRedis(sortedSetsScenario)(RedisCodec(LongCodec)))

  test("strings api")(withRedis(stringsScenario))

  test("connection api")(withRedis(connectionScenario))

  test("pipelining")(withRedis(pipelineScenario))

  test("server")(withRedis(serverScenario))

  test("transactions: successful")(withRedis(transactionScenario))

  test("transactions (double set): successful")(withRedis(transactionDoubleSetScenario))

  test("transactions: canceled")(withRedis(canceledTransactionScenario))

  test("scripts")(withRedis(scriptsScenario))

  test("hyperloglog api")(withRedis(hyperloglogScenario))

}

object LongCodec extends JRedisCodec[String, Long] with ToByteBufEncoder[String, Long] {

  import java.nio.ByteBuffer

  private val codec = JStringCodec.UTF8

  override def decodeKey(bytes: ByteBuffer): String            = codec.decodeKey(bytes)
  override def encodeKey(key: String): ByteBuffer              = codec.encodeKey(key)
  override def encodeValue(value: Long): ByteBuffer            = codec.encodeValue(value.toString)
  override def decodeValue(bytes: ByteBuffer): Long            = codec.decodeValue(bytes).toLong
  override def encodeKey(key: String, target: ByteBuf): Unit   = codec.encodeKey(key, target)
  override def encodeValue(value: Long, target: ByteBuf): Unit = codec.encodeValue(value.toString, target)
  override def estimateSize(keyOrValue: scala.Any): Int        = codec.estimateSize(keyOrValue)
}
