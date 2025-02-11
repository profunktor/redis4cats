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
import dev.profunktor.redis4cats.Redis4CatsFunSuite.Fs2Streaming
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data.{ RedisChannel, RedisCodec }
import dev.profunktor.redis4cats.effect.Log.NoOp._
import dev.profunktor.redis4cats.streams.{ RedisStream, Streaming }

import scala.concurrent.duration.{ Duration, DurationInt, FiniteDuration }
import scala.concurrent.{ Await, Future }
import dev.profunktor.redis4cats.pubsub.{ PubSub, PubSubCommands }
import dev.profunktor.redis4cats.Redis4CatsFunSuite.Fs2PubSub
import dev.profunktor.redis4cats.pubsub.data.Subscription
import munit.{ Compare, Location }

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
    (for {
      client <- fs2.Stream.resource(RedisClient[IO].from("redis://localhost"))
      pubSub <- fs2.Stream.resource(PubSub.mkPubSubConnection[IO, String, String](client, stringCodec))
      _ <- fs2.Stream.eval(f(pubSub))
    } yield ()).compile.drain.void.unsafeToFuture()

  def withRedisStream(f: (Fs2Streaming[String, String], Fs2Streaming[String, String]) => IO[Unit]): Future[Unit] =
    (for {
      client <- fs2.Stream.resource(RedisClient[IO].from("redis://localhost"))
      readStream <- RedisStream.mkStreamingConnection[IO, String, String](client, stringCodec)
      writeStream <- RedisStream.mkStreamingConnection[IO, String, String](client, stringCodec)
      _ <- fs2.Stream.eval(f(readStream, writeStream))
    } yield ()).compile.drain.void.unsafeToFuture()

  private def flushAll(): Future[Unit] =
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

  implicit class PubSubExts(pubSub: Fs2PubSub[String, String]) {

    /** Assert that a given channel has the given number of subscriptions.
      *
      * @param waitFor max time to wait for the expected number of subscriptions to be present
      * */
    def shouldHaveNSubs(
        channel: RedisChannel[String],
        count: Long,
        waitFor: FiniteDuration = 0.nanos
    )(implicit loc: Location): IO[Unit] =
      waitUntilEquals(
        pubSub.pubSubSubscriptions(List(channel)),
        List(Subscription(channel, count)),
        waitFor
      )
  }

  case class FiberWithStatus[A](fiber: FiberIO[A], status: Ref[IO, Option[Either[Unit, OutcomeIO[A]]]]) {
    def isRunning: IO[Boolean] = status.get.map(_.contains(Left(())))

    def waitUntilRunning(timeout: FiniteDuration = 250.millis): IO[Unit] =
      waitUntilEquals(isRunning, true, timeout, s"fiber $fiber should have started by now")
  }
  implicit class IOExts[A](io: IO[A]) {
    def startWithStatus: IO[FiberWithStatus[A]] =
      for {
        status <- Ref[IO].of(Option.empty[Either[Unit, OutcomeIO[A]]])
        fiber <- (status.set(Some(Left(()))) *> io.guaranteeCase(outcome => status.set(Some(Right(outcome))))).start
      } yield FiberWithStatus(fiber, status)

    def startAndWaitUntilRunning(timeout: FiniteDuration = 250.millis): IO[FiberIO[A]] =
      io.startWithStatus.flatTap(_.waitUntilRunning(timeout)).map(_.fiber)
  }

  /** Waits at most `waitFor` until the `io` starts returning `expected`, failing the assertion otherwise. */
  def waitUntilEquals[A, B](
      io: IO[A],
      expected: B,
      waitFor: FiniteDuration,
      clue: => Any = "values are not the same"
  )(implicit loc: Location, compare: Compare[A, B]): IO[Unit] = {
    def rec(startAt: FiniteDuration): IO[Unit] =
      for {
        actual <- io
        now <- IO.monotonic
        timePassed = now - startAt
        isEqual    = compare.isEqual(actual, expected)
        _ <- if (isEqual) IO.unit
            else if (timePassed <= waitFor) rec(startAt)
            else IO(assertEquals(actual, expected, clue))
      } yield ()

    IO.monotonic.flatMap(rec)
  }
}
object Redis4CatsFunSuite {
  type Fs2PubSub[K, V] = PubSubCommands[IO, fs2.Stream[IO, *], K, V]

  type Fs2Streaming[K, V] = Streaming[IO, fs2.Stream[IO, *], K, V]
}
