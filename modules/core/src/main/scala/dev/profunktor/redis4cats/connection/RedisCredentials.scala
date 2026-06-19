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

package dev.profunktor.redis4cats.connection

sealed trait RedisCredentials

object RedisCredentials {

  /** A password (or auth token) with no username — Redis `AUTH <password>`. */
  final case class Password(password: CharSequence) extends RedisCredentials

  /** A username and password (or auth token) — Redis 6 ACL style `AUTH <username> <password>`. */
  final case class UsernameAndPassword(username: String, password: CharSequence) extends RedisCredentials
}
