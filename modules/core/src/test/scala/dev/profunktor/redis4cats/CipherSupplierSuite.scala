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

package dev.profunktor.redis4cats

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import dev.profunktor.redis4cats.data.RedisCodec
import io.lettuce.core.codec.StringCodec
import munit.FunSuite

import java.nio.ByteBuffer
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec

class CipherSupplierSuite extends FunSuite {

  implicit val ioRuntime: IORuntime = IORuntime.global

  private def newKey(): SecretKeySpec = {
    val kg = KeyGenerator.getInstance("AES")
    kg.init(128)
    new SecretKeySpec(kg.generateKey().getEncoded, "AES")
  }

  test("encrypt/decrypt round-trips a value") {
    val key = newKey()
    val codec = (for {
      encrypt <- RedisCodec.encryptSupplier[IO](key)
      decrypt <- RedisCodec.decryptSupplier[IO](key)
    } yield RedisCodec.secure(RedisCodec(StringCodec.UTF8), encrypt, decrypt)).unsafeRunSync()

    val encoded = codec.underlying.encodeValue("hello world")
    val decoded = codec.underlying.decodeValue(encoded)
    assertEquals(decoded, "hello world")
  }

  test("encrypting the same plaintext twice produces different ciphertext (random IV per call)") {
    val key     = newKey()
    val encrypt = RedisCodec.encryptSupplier[IO](key).unsafeRunSync()
    val codec   = RedisCodec.secure(RedisCodec(StringCodec.UTF8), encrypt, encrypt).underlying

    val first: ByteBuffer  = codec.encodeValue("same plaintext")
    val second: ByteBuffer = codec.encodeValue("same plaintext")

    assertNotEquals(first, second)
  }

  test("a CipherSupplier hands out a fresh Cipher per call, per Lettuce's CipherSupplier contract") {
    val supplier = RedisCodec.encryptSupplier[IO](newKey()).unsafeRunSync()
    val kd       = supplier.encryptionKey()
    assert(supplier.get(kd) ne supplier.get(kd))
  }
}
