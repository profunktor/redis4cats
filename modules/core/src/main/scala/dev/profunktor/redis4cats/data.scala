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

import cats.effect.Sync
import cats.syntax.functor._
import dev.profunktor.redis4cats.JavaConversions._
import io.lettuce.core.{ ReadFrom => JReadFrom }
import io.lettuce.core.codec.{ ByteArrayCodec, CipherCodec, CompressionCodec, RedisCodec => JRedisCodec, StringCodec }
import io.lettuce.core.{ KeyScanCursor => JKeyScanCursor, ScanCursor => JScanCursor }
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object data {

  final case class RedisChannel[K](underlying: K) extends AnyVal

  final case class RedisCodec[K, V](underlying: JRedisCodec[K, V]) extends AnyVal
  final case class NodeId(value: String) extends AnyVal

  sealed abstract class ScanCursor {
    def underlying: JScanCursor

    def isFinished: Boolean = underlying.isFinished

    def cursor: String = underlying.getCursor
  }

  final case class KeyScanCursor[K](underlying: JKeyScanCursor[K]) extends ScanCursor {
    def keys: List[K] = underlying.getKeys.asScala.toList
  }

  object RedisCodec {
    val Ascii: RedisCodec[String, String]           = RedisCodec(StringCodec.ASCII)
    val Utf8: RedisCodec[String, String]            = RedisCodec(StringCodec.UTF8)
    val Bytes: RedisCodec[Array[Byte], Array[Byte]] = RedisCodec(ByteArrayCodec.INSTANCE)

    /**
      * It compresses every value sent to Redis and it decompresses every value read
      * from Redis using the DEFLATE compression algorithm.
      */
    def deflate[K, V](codec: RedisCodec[K, V]): RedisCodec[K, V] =
      RedisCodec(CompressionCodec.valueCompressor(codec.underlying, CompressionCodec.CompressionType.DEFLATE))

    /**
      * It compresses every value sent to Redis and it decompresses every value read
      * from Redis using the GZIP compression algorithm.
      */
    def gzip[K, V](codec: RedisCodec[K, V]): RedisCodec[K, V] =
      RedisCodec(CompressionCodec.valueCompressor(codec.underlying, CompressionCodec.CompressionType.GZIP))

    /**
      * It encrypts every value sent to Redis and it decrypts every value read from
      * Redis using the supplied CipherSuppliers.
      */
    def secure[K, V](
        codec: RedisCodec[K, V],
        encrypt: CipherCodec.CipherSupplier,
        decrypt: CipherCodec.CipherSupplier
    ): RedisCodec[K, V] =
      RedisCodec(CipherCodec.forValues(codec.underlying, encrypt, decrypt))

    /**
      * It creates a CipherSupplier given a secret key for encryption.
      *
      * A CipherSupplier is needed for [[RedisCodec.secure]]
      */
    def encryptSupplier[F[_]: Sync](key: SecretKeySpec): F[CipherCodec.CipherSupplier] =
      cipherSupplier[F](key, Cipher.ENCRYPT_MODE)

    /**
      * It creates a CipherSupplier given a secret key for decryption.
      *
      * A CipherSupplier is needed for [[RedisCodec.secure]]
      */
    def decryptSupplier[F[_]: Sync](key: SecretKeySpec): F[CipherCodec.CipherSupplier] =
      cipherSupplier[F](key, Cipher.DECRYPT_MODE)

    private def cipherSupplier[F[_]: Sync](key: SecretKeySpec, mode: Int): F[CipherCodec.CipherSupplier] = {
      val mkCipher =
        F.delay {
          val cipher: Cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
          cipher.init(mode, key)
          cipher
        }

      mkCipher.map { cipher =>
        new CipherCodec.CipherSupplier {
          override def get(kd: CipherCodec.KeyDescriptor): Cipher = cipher
        }
      }
    }

  }

  object ReadFrom {
    @deprecated(message = "in favor of Upstream", since = "v0.10.4")
    val Master = JReadFrom.UPSTREAM
    @deprecated(message = "in favor of UpstreamPreferred", since = "v0.10.4")
    val MasterPreferred   = JReadFrom.UPSTREAM_PREFERRED
    val Upstream          = JReadFrom.UPSTREAM
    val UpstreamPreferred = JReadFrom.UPSTREAM_PREFERRED
    val Nearest           = JReadFrom.NEAREST
    val Replica           = JReadFrom.REPLICA
    val ReplicaPreferred  = JReadFrom.REPLICA_PREFERRED
  }

}
