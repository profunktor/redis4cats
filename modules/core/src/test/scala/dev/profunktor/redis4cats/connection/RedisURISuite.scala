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

import cats.data.NonEmptyList
import io.lettuce.core.StaticCredentialsProvider
import io.lettuce.core.{ SslVerifyMode => JSslVerifyMode }
import munit.FunSuite

import scala.concurrent.duration._

class RedisURISuite extends FunSuite {

  private type ErrOr[A] = Either[Throwable, A]

  // Lettuce 6.8.2: RedisURI.getPassword/getUsername are deprecated (and -Wconf turns the
  // warning into an error), so we read the credentials through the provider. Both withPassword
  // and withAuthentication store into the username/password fields, so getCredentialsProvider
  // returns a StaticCredentialsProvider; the cast is safe for the pinned Lettuce version.
  private def creds(uri: RedisURI): io.lettuce.core.RedisCredentials =
    uri.underlying.getCredentialsProvider
      .asInstanceOf[StaticCredentialsProvider]
      .resolveCredentialsNow()

  private def usernameOf(uri: RedisURI): Option[String] =
    Option(creds(uri).getUsername)

  private def passwordOf(uri: RedisURI): String =
    Option(creds(uri).getPassword).map(new String(_)).orNull

  test("withCredentials(Password) sets the password and leaves the username unset") {
    val uri =
      RedisURI.unsafeFromString("redis://localhost:6379/0").withCredentials(RedisCredentials.Password("tok@123"))

    assertEquals(usernameOf(uri), None)
    assertEquals(passwordOf(uri), "tok@123")
  }

  test("withCredentials(UsernameAndPassword) sets both username and password") {
    val uri = RedisURI
      .unsafeFromString("redis://localhost:6379/0")
      .withCredentials(RedisCredentials.UsernameAndPassword("alice", "tok@123"))

    assertEquals(usernameOf(uri), Some("alice"))
    assertEquals(passwordOf(uri), "tok@123")
  }

  test("withCredentials preserves host, port and database") {
    val uri =
      RedisURI.unsafeFromString("redis://localhost:6379/2").withCredentials(RedisCredentials.Password("tok"))

    assertEquals(uri.underlying.getHost, "localhost")
    assertEquals(uri.underlying.getPort, 6379)
    assertEquals(uri.underlying.getDatabase, 2)
  }

  private def unsafeCfg(config: RedisUriConfig): RedisURI =
    RedisURI.fromConfig[ErrOr](config).fold(throw _, identity)

  test("fromConfig Standalone maps host, port, database, timeout, clientName and credentials") {
    val uri = unsafeCfg(
      RedisUriConfig(
        endpoint = RedisEndpoint.Standalone("redis.example.com", 6380),
        credentials = Some(RedisCredentials.UsernameAndPassword("alice", "tok@123")),
        database = Some(3),
        timeout = Some(5.seconds),
        clientName = Some("app-1")
      )
    )
    assertEquals(uri.underlying.getHost, "redis.example.com")
    assertEquals(uri.underlying.getPort, 6380)
    assertEquals(uri.underlying.getDatabase, 3)
    assertEquals(uri.underlying.getTimeout, java.time.Duration.ofSeconds(5))
    assertEquals(uri.underlying.getClientName, "app-1")
    assertEquals(usernameOf(uri), Some("alice"))
    assertEquals(passwordOf(uri), "tok@123")
  }

  test("fromConfig with TLS enables ssl and maps startTls + verifyPeer") {
    val uri = unsafeCfg(
      RedisUriConfig(
        endpoint = RedisEndpoint.Standalone("localhost"),
        tls = Some(TlsConfig(startTls = true, verifyPeer = SslVerifyMode.None))
      )
    )
    assertEquals(uri.underlying.isSsl, true)
    assertEquals(uri.underlying.isStartTls, true)
    assertEquals(uri.underlying.getVerifyMode, JSslVerifyMode.NONE)
  }

  test("fromConfig without TLS leaves ssl disabled") {
    val uri = unsafeCfg(RedisUriConfig(endpoint = RedisEndpoint.Standalone("localhost")))
    assertEquals(uri.underlying.isSsl, false)
  }

  test("fromConfig Socket sets the socket path") {
    val uri = unsafeCfg(RedisUriConfig(endpoint = RedisEndpoint.Socket("/tmp/redis.sock")))
    assertEquals(uri.underlying.getSocket, "/tmp/redis.sock")
  }

  test("fromConfig Sentinel sets master id and all nodes") {
    val uri = unsafeCfg(
      RedisUriConfig(
        endpoint = RedisEndpoint.Sentinel(
          "mymaster",
          NonEmptyList.of(SentinelNode("h1", 26379), SentinelNode("h2", 26380))
        )
      )
    )
    assertEquals(uri.underlying.getSentinelMasterId, "mymaster")
    assertEquals(uri.underlying.getSentinels.size, 2)
    assertEquals(uri.underlying.getSentinels.get(0).getHost, "h1")
    assertEquals(uri.underlying.getSentinels.get(1).getPort, 26380)
  }

  test("fromConfig Sentinel places a node password on the sentinel node, not the main URI") {
    val uri = unsafeCfg(
      RedisUriConfig(
        endpoint = RedisEndpoint.Sentinel(
          "mymaster",
          NonEmptyList.of(SentinelNode("h1", 26379, Some("sentpw")))
        )
      )
    )
    // password is on the sentinel node ...
    assertEquals(passwordOf(RedisURI.fromUnderlying(uri.underlying.getSentinels.get(0))), "sentpw")
    // ... and NOT on the main URI
    assertEquals(passwordOf(uri), null)
  }

  test("fromConfig maps libraryName and libraryVersion") {
    val uri = unsafeCfg(
      RedisUriConfig(
        endpoint = RedisEndpoint.Standalone("localhost"),
        libraryName = Some("redis4cats"),
        libraryVersion = Some("2.x")
      )
    )
    assertEquals(uri.underlying.getLibraryName, "redis4cats")
    assertEquals(uri.underlying.getLibraryVersion, "2.x")
  }

  test("fromConfig Sentinel maps per-node passwords across multiple nodes") {
    val uri = unsafeCfg(
      RedisUriConfig(
        endpoint = RedisEndpoint.Sentinel(
          "mymaster",
          NonEmptyList.of(
            SentinelNode("h1", 26379, Some("pw1")),
            SentinelNode("h2", 26380)
          )
        )
      )
    )
    assertEquals(uri.underlying.getSentinels.size, 2)
    assertEquals(passwordOf(RedisURI.fromUnderlying(uri.underlying.getSentinels.get(0))), "pw1")
    assertEquals(passwordOf(RedisURI.fromUnderlying(uri.underlying.getSentinels.get(1))), null)
  }

  test("fromConfig maps SslVerifyMode.Full and Ca to the Lettuce enum") {
    val full = unsafeCfg(
      RedisUriConfig(
        endpoint = RedisEndpoint.Standalone("localhost"),
        tls = Some(TlsConfig(verifyPeer = SslVerifyMode.Full))
      )
    )
    assertEquals(full.underlying.getVerifyMode, JSslVerifyMode.FULL)

    val ca = unsafeCfg(
      RedisUriConfig(
        endpoint = RedisEndpoint.Standalone("localhost"),
        tls = Some(TlsConfig(verifyPeer = SslVerifyMode.Ca))
      )
    )
    assertEquals(ca.underlying.getVerifyMode, JSslVerifyMode.CA)
  }

  test("RedisUriConfig.standalone + fluent withX equals the explicit case-class form") {
    val viaHelpers = RedisUriConfig
      .standalone("h", 1)
      .withCredentials(RedisCredentials.Password("tok"))
      .withTls(TlsConfig(verifyPeer = SslVerifyMode.None))
      .withDatabase(2)
      .withTimeout(5.seconds)
      .withClientName("c")
      .withLibraryName("ln")
      .withLibraryVersion("lv")

    val explicit = RedisUriConfig(
      endpoint = RedisEndpoint.Standalone("h", 1),
      credentials = Some(RedisCredentials.Password("tok")),
      tls = Some(TlsConfig(verifyPeer = SslVerifyMode.None)),
      database = Some(2),
      timeout = Some(5.seconds),
      clientName = Some("c"),
      libraryName = Some("ln"),
      libraryVersion = Some("lv")
    )

    assertEquals(viaHelpers, explicit)
  }

  test("withTls() defaults to an enabled TlsConfig") {
    assertEquals(RedisUriConfig.standalone("h").withTls().tls, Some(TlsConfig()))
  }

  test("RedisUriConfig.socket builds a Socket endpoint") {
    assertEquals(RedisUriConfig.socket("/tmp/x.sock").endpoint, RedisEndpoint.Socket("/tmp/x.sock"))
  }

  test("RedisUriConfig.sentinel varargs builds the NonEmptyList of nodes") {
    val cfg = RedisUriConfig.sentinel("m", SentinelNode("h1"), SentinelNode("h2", 26380))
    assertEquals(
      cfg.endpoint,
      RedisEndpoint.Sentinel("m", NonEmptyList.of(SentinelNode("h1"), SentinelNode("h2", 26380)))
    )
  }

  test("SentinelNode.withPassword wraps the password") {
    assertEquals(SentinelNode("h1").withPassword("pw"), SentinelNode("h1", 26379, Some("pw")))
  }

  test("fromConfig Sentinel keeps distinct per-node passwords on the correct nodes") {
    val uri = unsafeCfg(
      RedisUriConfig.sentinel(
        "mymaster",
        SentinelNode("h1", 26379).withPassword("pwA"),
        SentinelNode("h2", 26380).withPassword("pwB")
      )
    )
    assertEquals(passwordOf(RedisURI.fromUnderlying(uri.underlying.getSentinels.get(0))), "pwA")
    assertEquals(passwordOf(RedisURI.fromUnderlying(uri.underlying.getSentinels.get(1))), "pwB")
  }
}
