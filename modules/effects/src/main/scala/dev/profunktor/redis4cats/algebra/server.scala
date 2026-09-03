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

import dev.profunktor.redis4cats.effects.{ FlushMode, RedisServerTime }
import io.lettuce.core.{ ClientListArgs, KillArgs, UnblockType }

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

trait ServerCommands[F[_], K, V]
    extends Flush[F, K]
    with Diagnostic[F, V]
    with Config[F]
    with ClientAdmin[F]
    with Maintenance[F, K]

trait Flush[F[_], K] {
  def keys(key: String): F[List[K]]
  def flushAll: F[Unit]
  def flushAll(mode: FlushMode): F[Unit]
  def flushDb: F[Unit]
  def flushDb(mode: FlushMode): F[Unit]
}

trait Diagnostic[F[_], V] {
  def info: F[Map[String, String]]
  def info(section: String): F[Map[String, String]]
  def dbsize: F[Long]
  def lastSave: F[Instant]
  def slowLogLen: F[Long]
  def slowLogReset: F[Unit]
  def commandCount: F[Long]
  def time: F[RedisServerTime]
}

trait Config[F[_]] {
  def configGet(parameter: String): F[Map[String, String]]
  def configGet(parameters: String*): F[Map[String, String]]
  def configSet(parameter: String, value: String): F[Unit]
  def configSet(values: Map[String, String]): F[Unit]
  def configResetStat: F[Unit]
  def configRewrite: F[Unit]
}

trait ClientAdmin[F[_]] {
  def clientList: F[List[Map[String, String]]]
  def clientList(args: ClientListArgs): F[List[Map[String, String]]]
  def clientKill(addr: String): F[Unit]
  def clientKill(args: KillArgs): F[Long]
  def clientPause(timeout: FiniteDuration): F[Unit]
  def clientUnblock(id: Long, unblockType: UnblockType): F[Long]
  def clientGetRedir: F[Long]
  def clientCaching(enabled: Boolean): F[Unit]
  def clientNoTouch(enabled: Boolean): F[Unit]
  def clientNoEvict(enabled: Boolean): F[Unit]
}

trait Maintenance[F[_], K] {
  def memoryUsage(key: K): F[Option[Long]]
  def save: F[Unit]
  def bgSave: F[Unit]
  def bgRewriteAof: F[Unit]
}
