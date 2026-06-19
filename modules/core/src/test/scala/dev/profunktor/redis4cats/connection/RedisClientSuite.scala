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

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import dev.profunktor.redis4cats.effect.Log.NoOp._
import io.lettuce.core.ClientOptions
import io.lettuce.core.StaticCredentialsProvider
import munit.FunSuite

class RedisClientSuite extends FunSuite {

  implicit val ioRuntime: IORuntime = cats.effect.unsafe.IORuntime.global

  // RedisClient.create opens no socket (connection is explicit), and ShutdownConfig defaults to
  // quietPeriod=0, so building/releasing the resource needs no running Redis.
  private def passwordOf(uri: RedisURI): String =
    Option(
      uri.underlying.getCredentialsProvider
        .asInstanceOf[StaticCredentialsProvider]
        .resolveCredentialsNow()
        .getPassword
    ).map(new String(_)).orNull

  test("fromConfig builds a client whose URI reflects the config") {
    val (host, password) =
      RedisClient[IO]
        .fromConfig(
          RedisUriConfig(
            endpoint = RedisEndpoint.Standalone("redis.example.com", 6380),
            credentials = Some(RedisCredentials.Password("tok@123"))
          )
        )
        .use(client => IO.pure((client.uri.underlying.getHost, passwordOf(client.uri))))
        .unsafeRunSync()

    assertEquals(host, "redis.example.com")
    assertEquals(password, "tok@123")
  }

  test("fromConfig with ClientOptions builds a client whose URI reflects the config") {
    val (host, password) =
      RedisClient[IO]
        .fromConfig(
          RedisUriConfig(
            endpoint = RedisEndpoint.Standalone("redis.example.com", 6380),
            credentials = Some(RedisCredentials.Password("tok@123"))
          ),
          ClientOptions.create()
        )
        .use(client => IO.pure((client.uri.underlying.getHost, passwordOf(client.uri))))
        .unsafeRunSync()

    assertEquals(host, "redis.example.com")
    assertEquals(password, "tok@123")
  }
}
