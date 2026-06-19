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

import scala.concurrent.duration.FiniteDuration

import cats.data.NonEmptyList

/** SSL peer verification mode. Mirrors `io.lettuce.core.SslVerifyMode`. */
sealed trait SslVerifyMode
object SslVerifyMode {
  case object Full extends SslVerifyMode
  case object Ca extends SslVerifyMode
  case object None extends SslVerifyMode
}

/** TLS settings, present only when TLS is enabled. */
final case class TlsConfig(startTls: Boolean = false, verifyPeer: SslVerifyMode = SslVerifyMode.Full)

/** A single Redis Sentinel node. */
final case class SentinelNode(host: String, port: Int = 26379, password: Option[CharSequence] = None)

/** The Redis connection endpoint (mutually-exclusive modes). */
sealed trait RedisEndpoint
object RedisEndpoint {
  final case class Standalone(host: String = "localhost", port: Int = 6379) extends RedisEndpoint
  final case class Socket(path: String) extends RedisEndpoint
  final case class Sentinel(masterId: String, nodes: NonEmptyList[SentinelNode]) extends RedisEndpoint
}

/** Type-safe configuration for constructing a [[RedisURI]] with parity to Lettuce's `RedisURI`.
  *
  * Cross-cutting options (`tls`, `database`, `timeout`, etc.) are applied as given and are not validated against the
  * chosen `endpoint`. For example, combining `RedisEndpoint.Socket` with `tls` produces a URI carrying SSL flags even
  * though TLS over a unix socket is unusual; this mirrors Lettuce, which performs no such validation either.
  */
final case class RedisUriConfig(
    endpoint: RedisEndpoint,
    credentials: Option[RedisCredentials] = None,
    tls: Option[TlsConfig] = None,
    database: Option[Int] = None,
    timeout: Option[FiniteDuration] = None,
    clientName: Option[String] = None,
    libraryName: Option[String] = None,
    libraryVersion: Option[String] = None
)
