/*
 * Copyright 2018-2020 ProfunKtor
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

package dev.profunktor.redis4cats

import cats.effect._
import cats.implicits._
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log.noop
import org.scalatest.BeforeAndAfterEach
import org.scalatest.compatible.Assertion
import org.scalatest.funsuite.AsyncFunSuite
import scala.concurrent.ExecutionContext

class Redis4CatsFunSuite(isCluster: Boolean) extends AsyncFunSuite with BeforeAndAfterEach {

  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO]     = IO.timer(ExecutionContext.global)
  implicit val clock: Clock[IO]     = timer.clock

  override protected def afterEach(): Unit = {
    flushAll()
    super.afterEach()
  }

  private val stringCodec = RedisCodec.Utf8

  def withAbstractRedis[A, K, V](f: RedisCommands[IO, K, V] => IO[A])(codec: RedisCodec[K, V]): Assertion =
    Redis[IO].simple("redis://localhost", codec).use(f).as(assert(true)).unsafeRunSync()

  def withRedis[A](f: RedisCommands[IO, String, String] => IO[A]): Assertion =
    withAbstractRedis[A, String, String](f)(stringCodec)

  private def flushAll(): Assertion =
    if (isCluster) withRedisCluster(_.flushAll)
    else withRedis(_.flushAll)

  // --- Cluster ---

  lazy val redisUri = List(
    "redis://localhost:30001",
    "redis://localhost:30002",
    "redis://localhost:30003"
  ).traverse(RedisURI.make[IO](_))

  private def mkRedisCluster[K, V](codec: RedisCodec[K, V]): Resource[IO, RedisCommands[IO, K, V]] =
    for {
      uris <- Resource.liftF(redisUri)
      client <- RedisClusterClient[IO](uris: _*)
      cluster <- Redis[IO].fromClusterClient(client, codec)
    } yield cluster

  def withAbstractRedisCluster[A, K, V](
      f: RedisCommands[IO, K, V] => IO[A]
  )(codec: RedisCodec[K, V]): Assertion =
    mkRedisCluster(codec).use(f).as(assert(true)).unsafeRunSync()

  def withRedisCluster[A](f: RedisCommands[IO, String, String] => IO[A]): Assertion =
    withAbstractRedisCluster[A, String, String](f)(stringCodec)

}
