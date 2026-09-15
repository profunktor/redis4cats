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

import cats.ApplicativeThrow
import cats.implicits.toBifunctorOps
import io.lettuce.core.{ RedisURI => JRedisURI, SslVerifyMode => JSslVerifyMode }

import scala.util.Try
import scala.util.control.NoStackTrace

sealed abstract class RedisURI private (val underlying: JRedisURI) {

  /** Returns a new [[RedisURI]] with the given static [[RedisCredentials]] attached.
    *
    * Host, port, database, SSL and all other settings are preserved. The original URI is not mutated. Tokens containing
    * URI-reserved characters (e.g. `@`, `:`, `/`) need no escaping.
    */
  def withCredentials(credentials: RedisCredentials): RedisURI = {
    // JRedisURI.builder(underlying) copies ssl/auth/timeout/database/clientName/host-or-socket, but not
    // the Sentinel master id or sentinel node list - those have to be copied over separately.
    val builder = JRedisURI.builder(underlying)
    Option(underlying.getSentinelMasterId).foreach(builder.withSentinelMasterId)
    underlying.getSentinels.forEach(sentinel => builder.withSentinel(sentinel): Unit)
    RedisURI.fromUnderlying(RedisURI.applyCredentials(builder, credentials).build())
  }
}

object RedisURI {
  def make[F[_]: ApplicativeThrow](uri: => String): F[RedisURI] =
    ApplicativeThrow[F].catchNonFatal(new RedisURI(JRedisURI.create(uri)) {})

  def fromUnderlying(j: JRedisURI): RedisURI = new RedisURI(j) {}

  def fromString(uri: String): Either[InvalidRedisURI, RedisURI] =
    Try(JRedisURI.create(uri)).toEither.bimap(InvalidRedisURI(uri, _), new RedisURI(_) {})

  def unsafeFromString(uri: String): RedisURI = new RedisURI(JRedisURI.create(uri)) {}

  private[connection] def applyCredentials(b: JRedisURI.Builder, credentials: RedisCredentials): JRedisURI.Builder =
    credentials match {
      case RedisCredentials.Password(password)                      => b.withPassword(password)
      case RedisCredentials.UsernameAndPassword(username, password) => b.withAuthentication(username, password)
    }

  private def toJVerifyMode(mode: SslVerifyMode): JSslVerifyMode =
    mode match {
      case SslVerifyMode.Full => JSslVerifyMode.FULL
      case SslVerifyMode.Ca   => JSslVerifyMode.CA
      case SslVerifyMode.None => JSslVerifyMode.NONE
    }

  def unsafeFromConfig(config: RedisUriConfig): RedisURI = {
    val base: JRedisURI.Builder = config.endpoint match {
      case RedisEndpoint.Standalone(host, port) => JRedisURI.Builder.redis(host, port)
      case RedisEndpoint.Socket(path)           => JRedisURI.Builder.socket(path)
      case RedisEndpoint.Sentinel(masterId, nodes) =>
        nodes.foldLeft(JRedisURI.builder().withSentinelMasterId(masterId)) { (b, n) =>
          n.password match {
            case Some(pw) => b.withSentinel(n.host, n.port, pw)
            case None     => b.withSentinel(n.host, n.port)
          }
        }
    }

    val transforms: List[JRedisURI.Builder => JRedisURI.Builder] = List(
      config.credentials.map(c => (b: JRedisURI.Builder) => applyCredentials(b, c)),
      config.tls.map(t =>
        (b: JRedisURI.Builder) => b.withSsl(true).withStartTls(t.startTls).withVerifyPeer(toJVerifyMode(t.verifyPeer))
      ),
      config.database.map(d => (b: JRedisURI.Builder) => b.withDatabase(d)),
      config.timeout.map(t => (b: JRedisURI.Builder) => b.withTimeout(java.time.Duration.ofNanos(t.toNanos))),
      config.clientName.map(n => (b: JRedisURI.Builder) => b.withClientName(n)),
      config.libraryName.map(n => (b: JRedisURI.Builder) => b.withLibraryName(n)),
      config.libraryVersion.map(v => (b: JRedisURI.Builder) => b.withLibraryVersion(v))
    ).flatten

    fromUnderlying(transforms.foldLeft(base)((b, f) => f(b)).build())
  }

  def fromConfig[F[_]: ApplicativeThrow](config: RedisUriConfig): F[RedisURI] =
    ApplicativeThrow[F].catchNonFatal(unsafeFromConfig(config))
}

final case class InvalidRedisURI(uri: String, throwable: Throwable) extends NoStackTrace {
  override def getMessage: String = Option(throwable.getMessage).getOrElse(s"Invalid Redis URI: $uri")
}
