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

package dev.profunktor.redis4cats.algebra

import java.time.Instant
import dev.profunktor.redis4cats.data.KeyScanCursor
import dev.profunktor.redis4cats.effects.{
  CompareCondition,
  CopyArgs,
  ExpireExistenceArg,
  KeyScanArgs,
  MigrateArgs,
  RedisType,
  RestoreArgs,
  ScanArgs,
  SortArgs
}

import scala.concurrent.duration.FiniteDuration

trait KeyCommands[F[_], K, V] {
  def copy(source: K, destination: K): F[Boolean]
  def copy(source: K, destination: K, copyArgs: CopyArgs): F[Boolean]
  def del(k: K, keys: K*): F[Long]

  /** Deletes `key` only if `condition` holds against its current value/digest - Redis's `DELEX`. `false` if the key
    * didn't exist or the condition didn't hold; any other failure raises in `F` as usual.
    */
  def delex(key: K, condition: CompareCondition[V]): F[Boolean]

  def dump(key: K): F[Option[Array[Byte]]]
  def exists(key: K, keys: K*): F[Boolean]
  def expire(key: K, expiresIn: FiniteDuration): F[Boolean]
  def expire(key: K, expiresIn: FiniteDuration, expireExistenceArg: ExpireExistenceArg): F[Boolean]
  def expireAt(key: K, at: Instant): F[Boolean]
  def expireAt(key: K, at: Instant, expireExistenceArg: ExpireExistenceArg): F[Boolean]
  def objectIdletime(key: K): F[Option[FiniteDuration]]
  def objectEncoding(key: K): F[Option[String]]
  def objectFreq(key: K): F[Long]
  def objectRefcount(key: K): F[Long]
  def persist(key: K): F[Boolean]
  def pttl(key: K): F[Option[FiniteDuration]]
  def randomKey: F[Option[K]]
  def rename(key: K, newKey: K): F[Unit]
  def renameNx(key: K, newKey: K): F[Boolean]
  // restores a key with the given serialized value, previously obtained using DUMP without a ttl
  def restore(key: K, value: Array[Byte]): F[Unit]
  def restore(key: K, value: Array[Byte], restoreArgs: RestoreArgs): F[Unit]
  def scan: F[KeyScanCursor[K]]
  @deprecated("In favor of scan(cursor: KeyScanCursor[K])", since = "0.10.4")
  def scan(cursor: Long): F[KeyScanCursor[K]]
  def scan(previous: KeyScanCursor[K]): F[KeyScanCursor[K]]
  @deprecated("In favor of scan(keyScanArgs: KeyScanArgs)", since = "1.7.2")
  def scan(scanArgs: ScanArgs): F[KeyScanCursor[K]]
  def scan(keyScanArgs: KeyScanArgs): F[KeyScanCursor[K]]
  @deprecated("In favor of scan(cursor: KeyScanCursor[K], scanArgs: ScanArgs)", since = "0.10.4")
  def scan(cursor: Long, scanArgs: ScanArgs): F[KeyScanCursor[K]]
  @deprecated("In favor of scan(previous: KeyScanCursor[K], keyScanArgs: KeyScanArgs)", since = "1.7.2")
  def scan(previous: KeyScanCursor[K], scanArgs: ScanArgs): F[KeyScanCursor[K]]
  def scan(cursor: KeyScanCursor[K], keyScanArgs: KeyScanArgs): F[KeyScanCursor[K]]
  def sort(key: K): F[List[V]]
  def sort(key: K, sortArgs: SortArgs): F[List[V]]
  def sortReadOnly(key: K): F[List[V]]
  def sortReadOnly(key: K, sortArgs: SortArgs): F[List[V]]
  def sortStore(key: K, sortArgs: SortArgs, destination: K): F[Long]
  def typeOf(key: K): F[Option[RedisType]]
  def ttl(key: K): F[Option[FiniteDuration]]
  def expireTime(key: K): F[Option[Instant]]
  def pExpireTime(key: K): F[Option[Instant]]
  def move(key: K, db: Int): F[Boolean]

  /** Moves `key` to a different Redis instance. Returns `false` for Redis's own "NOKEY" reply (the key didn't exist),
    * `true` on success - any other failure (auth, connection, timeout) raises in `F` as usual.
    */
  def migrate(host: String, port: Int, key: K, destinationDb: Int, timeout: FiniteDuration): F[Boolean]

  /** Multi-key form of [[migrate]], via Lettuce's [[io.lettuce.core.MigrateArgs]] (COPY/REPLACE/AUTH/multiple keys). */
  def migrate(host: String, port: Int, destinationDb: Int, timeout: FiniteDuration, args: MigrateArgs[K]): F[Boolean]

  def touch(key: K, keys: K*): F[Long]
  // This command is very similar to DEL: it removes the specified keys. Just like DEL a key is ignored if it does not exist. However the command performs the actual memory reclaiming in a different thread, so it is not blocking, while DEL is. This is where the command name comes from: the command just unlinks the keys from the keyspace. The actual removal will happen later asynchronously.
  def unlink(key: K*): F[Long]

}
