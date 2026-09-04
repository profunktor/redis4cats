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

import cats.effect.kernel.Sync
import cats.syntax.functor._
import dev.profunktor.redis4cats.JavaConversions._
import io.lettuce.core.codec.{ ByteArrayCodec, CipherCodec, CompressionCodec, RedisCodec => JRedisCodec, StringCodec }
import io.lettuce.core.{
  KeyScanCursor => JKeyScanCursor,
  MapScanCursor => JMapScanCursor,
  ReadFrom => JReadFrom,
  ScanCursor => JScanCursor,
  ValueScanCursor => JValueScanCursor
}

import javax.crypto.Cipher
import javax.crypto.spec.{ IvParameterSpec, SecretKeySpec }
import java.security.SecureRandom
import java.{ util => ju }
import java.lang.{ Long => JLong }

object data {

  final case class RedisChannel[K](underlying: K) extends AnyVal
  final case class RedisPattern[K](underlying: K) extends AnyVal
  final case class RedisPatternEvent[K, V](pattern: K, channel: K, data: V)

  /** Represents a pub/sub subscription with its subscriber count.
    *
    * @param channel
    *   the subscribed channel
    * @param number
    *   the number of subscribers to this channel
    */
  final case class Subscription[K](channel: RedisChannel[K], number: Long)

  object Subscription {
    def empty[K](channel: RedisChannel[K]): Subscription[K] =
      Subscription[K](channel, 0L)

    def toSubscription[K](map: ju.Map[K, JLong]): List[Subscription[K]] =
      map.asScala.toList.map { case (k, n) => Subscription(RedisChannel[K](k), Long.unbox(n)) }
  }
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

  final case class MapScanCursor[K, V](underlying: JMapScanCursor[K, V]) extends ScanCursor {
    def map: Map[K, V] = underlying.getMap.asScala.toMap
  }

  final case class ValueScanCursor[V](underlying: JValueScanCursor[V]) extends ScanCursor {
    def values: List[V] = underlying.getValues.asScala.toList
  }

  object RedisCodec {
    val Ascii: RedisCodec[String, String]           = RedisCodec(StringCodec.ASCII)
    val Utf8: RedisCodec[String, String]            = RedisCodec(StringCodec.UTF8)
    val Bytes: RedisCodec[Array[Byte], Array[Byte]] = RedisCodec(ByteArrayCodec.INSTANCE)

    /** It compresses every value sent to Redis and it decompresses every value read from Redis using the DEFLATE
      * compression algorithm.
      */
    def deflate[K, V](codec: RedisCodec[K, V]): RedisCodec[K, V] =
      RedisCodec(CompressionCodec.valueCompressor(codec.underlying, CompressionCodec.CompressionType.DEFLATE))

    /** It compresses every value sent to Redis and it decompresses every value read from Redis using the GZIP
      * compression algorithm.
      */
    def gzip[K, V](codec: RedisCodec[K, V]): RedisCodec[K, V] =
      RedisCodec(CompressionCodec.valueCompressor(codec.underlying, CompressionCodec.CompressionType.GZIP))

    /** It encrypts every value sent to Redis and it decrypts every value read from Redis using the supplied
      * CipherSuppliers.
      */
    def secure[K, V](
        codec: RedisCodec[K, V],
        encrypt: CipherCodec.CipherSupplier,
        decrypt: CipherCodec.CipherSupplier
    ): RedisCodec[K, V] =
      RedisCodec(CipherCodec.forValues(codec.underlying, encrypt, decrypt))

    // CBC needs an IV, but Lettuce's CipherCodec wire format only carries a $name+version$ key descriptor -
    // no room for one. We smuggle a fresh random IV through as the descriptor's "name": encryptionKey()
    // generates it and get() decodes it back out, and since Lettuce round-trips the exact same descriptor
    // it read off the wire into the decrypt supplier's get(), the same IV comes back out on the way in. Key
    // name/version-based rotation (the feature this field exists for) isn't otherwise exposed by this API.
    private def ivOf(kd: CipherCodec.KeyDescriptor): IvParameterSpec =
      new IvParameterSpec(ju.Base64.getUrlDecoder.decode(kd.getName))

    /** It creates a CipherSupplier given a secret key for encryption.
      *
      * A CipherSupplier is needed for [[RedisCodec.secure]]
      */
    def encryptSupplier[F[_]: Sync](key: SecretKeySpec): F[CipherCodec.CipherSupplier] =
      Sync[F].delay(new SecureRandom).map { random =>
        new CipherCodec.CipherSupplier {
          override def encryptionKey(): CipherCodec.KeyDescriptor = {
            val iv = new Array[Byte](16)
            random.nextBytes(iv)
            CipherCodec.KeyDescriptor.create(ju.Base64.getUrlEncoder.withoutPadding.encodeToString(iv))
          }

          override def get(kd: CipherCodec.KeyDescriptor): Cipher = {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key, ivOf(kd))
            cipher
          }
        }
      }

    /** It creates a CipherSupplier given a secret key for decryption.
      *
      * A CipherSupplier is needed for [[RedisCodec.secure]]
      */
    def decryptSupplier[F[_]: Sync](key: SecretKeySpec): F[CipherCodec.CipherSupplier] =
      Sync[F].delay {
        new CipherCodec.CipherSupplier {
          override def get(kd: CipherCodec.KeyDescriptor): Cipher = {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, ivOf(kd))
            cipher
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
    @deprecated(message = "in favour of LowestLatency", since = "v1.2.0")
    val Nearest          = JReadFrom.LOWEST_LATENCY
    val LowestLatency    = JReadFrom.LOWEST_LATENCY
    val Replica          = JReadFrom.REPLICA
    val ReplicaPreferred = JReadFrom.REPLICA_PREFERRED
  }

}
