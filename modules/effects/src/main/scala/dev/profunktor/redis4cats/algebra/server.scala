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

package dev.profunktor.redis4cats.algebra

import java.time.Instant

trait ServerCommands[F[_], K] extends Flush[F, K] with Diagnostic[F]

trait Flush[F[_], K] {
  def keys(key: K): F[List[K]]
  def flushAll: F[Unit]
  def flushAllAsync: F[Unit]
}

trait Diagnostic[F[_]] {
  def info: F[Map[String, String]]
  def dbsize: F[Long]
  def lastSave: F[Instant]
  def slowLogLen: F[Long]
}
