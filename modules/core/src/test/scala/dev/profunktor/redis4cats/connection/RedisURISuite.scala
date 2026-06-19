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

import io.lettuce.core.StaticCredentialsProvider
import munit.FunSuite

class RedisURISuite extends FunSuite {

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
}
