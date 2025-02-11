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

package dev.profunktor.redis4cats

import cats.effect._
import cats.syntax.all._
import dev.profunktor.redis4cats.Redis4CatsFunSuite.{ Fs2PubSub, Fs2Streaming }
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log.NoOp._
import dev.profunktor.redis4cats.pubsub.{ PubSub, PubSubCommands }
import dev.profunktor.redis4cats.streams.{ RedisStream, Streaming }
import io.lettuce.core.{ ClientOptions, TimeoutOptions }

import scala.concurrent.duration.{ Duration, DurationInt }
import scala.concurrent.{ Await, Future }

abstract class Redis4CatsFunSuite(isCluster: Boolean) extends IOSuite {

  val flushAllFixture = new Fixture[Unit]("FLUSHALL") {
    def apply(): Unit = ()

    override def afterEach(context: AfterEach): Unit =
      Await.result(flushAll(), Duration.Inf)
  }

  override def munitFixtures = List(flushAllFixture)

  override def munitFlakyOK: Boolean = true

  private val stringCodec = RedisCodec.Utf8

  def withAbstractRedis[A, K, V](f: RedisCommands[IO, K, V] => IO[A])(codec: RedisCodec[K, V]): Future[Unit] =
    Redis[IO].simple("redis://localhost", codec).use(f).as(assert(true)).unsafeToFuture()

  def withRedis[A](f: RedisCommands[IO, String, String] => IO[A]): Future[Unit] =
    withAbstractRedis[A, String, String](f)(stringCodec)

  def withRedisClient[A](f: RedisClient => IO[A]): Future[Unit] =
    RedisClient[IO].from("redis://localhost").use(f).as(assert(true)).unsafeToFuture()

  def withRedisPubSub(f: Fs2PubSub[String, String] => IO[Unit]): Future[Unit] =
    withRedisPubSubOptionsResource(ClientOptions.create()).use(f).unsafeToFuture()

  def withRedisPubSubOptionsResource(options: ClientOptions): Resource[IO, Fs2PubSub[String, String]] =
    for {
      client <- RedisClient[IO].withOptions("redis://localhost", options)
      pubSub <- PubSub.mkPubSubConnection[IO, String, String](client, stringCodec)
    } yield pubSub

  def withRedisStream(f: (Fs2Streaming[String, String], Fs2Streaming[String, String]) => IO[Unit]): Future[Unit] =
    withRedisStreamOptionsResource(ClientOptions.create())
      .use { case (readStream, writeStream) => f(readStream, writeStream) }
      .unsafeToFuture()

  def withRedisStreamOptionsResource(
      options: ClientOptions
  ): Resource[IO, (Fs2Streaming[String, String], Fs2Streaming[String, String])] =
    for {
      client <- RedisClient[IO].withOptions("redis://localhost", options)
      readStream <- RedisStream.mkStreamingConnectionResource[IO, String, String](client, stringCodec)
      writeStream <- RedisStream.mkStreamingConnectionResource[IO, String, String](client, stringCodec)
    } yield (readStream, writeStream)

  private def flushAll(): Future[Unit] =
    if (isCluster) withRedisCluster(_.flushAll)
    else withRedis(_.flushAll)

  def timeoutingOperationTest[A](
      f: (ClientOptions, RestartOnTimeout) => fs2.Stream[IO, A]
  ): IO[Unit] = {
    val options = ClientOptions
      .builder()
      .timeoutOptions(TimeoutOptions.builder().fixedTimeout(java.time.Duration.ofMillis(250)).build())
      .build()

    f(options, RestartOnTimeout.always).interruptAfter(750.millis).compile.drain
  }

  // --- Cluster ---

  lazy val redisUri = List(
    "redis://localhost:30001",
    "redis://localhost:30002",
    "redis://localhost:30003"
  ).traverse(RedisURI.make[IO](_))

  private def mkRedisCluster[K, V](codec: RedisCodec[K, V]): Resource[IO, RedisCommands[IO, K, V]] =
    for {
      uris <- Resource.eval(redisUri)
      client <- RedisClusterClient[IO](uris: _*)
      cluster <- Redis[IO].fromClusterClient(client, codec)()
    } yield cluster

  def withAbstractRedisCluster[A, K, V](
      f: RedisCommands[IO, K, V] => IO[A]
  )(codec: RedisCodec[K, V]): Future[Unit] =
    mkRedisCluster(codec).use(f).as(assert(true)).unsafeToFuture()

  def withRedisCluster[A](f: RedisCommands[IO, String, String] => IO[A]): Future[Unit] =
    withAbstractRedisCluster[A, String, String](f)(stringCodec)

}
object Redis4CatsFunSuite {
  type Fs2PubSub[K, V] = PubSubCommands[IO, fs2.Stream[IO, *], K, V]

  type Fs2Streaming[K, V] = Streaming[IO, fs2.Stream[IO, *], K, V]
}
