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

import dev.profunktor.redis4cats.effects.{ BfInfo, BfInsertArgs, BfReserveArgs, BfScanDumpChunk }

trait BloomFilterCommands[F[_], K, V] {
  def bfAdd(key: K, value: V): F[Boolean]
  def bfCard(key: K): F[Long]
  def bfExists(key: K, value: V): F[Boolean]
  def bfInfo(key: K): F[BfInfo]
  def bfInsert(key: K, value: V, values: V*): F[List[Option[Boolean]]]
  def bfInsert(key: K, args: BfInsertArgs, value: V, values: V*): F[List[Option[Boolean]]]
  def bfLoadChunk(key: K, iterator: Long, data: Array[Byte]): F[Unit]
  def bfMAdd(key: K, value: V, values: V*): F[List[Option[Boolean]]]
  def bfMExists(key: K, value: V, values: V*): F[List[Boolean]]
  def bfReserve(key: K, errorRate: Double, capacity: Long): F[Unit]
  def bfReserve(key: K, errorRate: Double, capacity: Long, args: BfReserveArgs): F[Unit]

  /** Incremental save of a Bloom filter. `None` is returned when the iterator reaches 0, indicating that the scan is
    * complete.
    */
  def bfScanDump(key: K, iterator: Long): F[Option[BfScanDumpChunk]]
}
