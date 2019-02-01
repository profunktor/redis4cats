/*
 * Copyright 2018-2019 Gabriel Volpe
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

package com.github.gvolpe.fs2redis

import cats.effect.{ Clock, ContextShift, IO, Timer }
import cats.syntax.apply._
import cats.syntax.functor._
import com.github.gvolpe.fs2redis.connection.Fs2RedisClusterClient
import com.github.gvolpe.fs2redis.domain.{ DefaultRedisCodec, Fs2RedisCodec }
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.StringCodec
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, Suite }

import scala.concurrent.ExecutionContext

trait RedisClusterTest extends BeforeAndAfterAll with BeforeAndAfterEach { self: Suite =>
  import DockerRedis._, testLogger._

  // override this if the Redis container has to be started before invocation
  // when developing tests, this likely shall be false, so there is no additional overhead starting Redis
  lazy val startContainers: Boolean = true

  // override this to indicate whether containers shall be removed (true) once the test with Redis is done.
  lazy val clearContainers: Boolean = true

  lazy val firstPort = 30001
  lazy val lastPort  = 30006

  lazy val redisUri: List[RedisURI] = List(
    "redis://localhost:30001",
    "redis://localhost:30002",
    "redis://localhost:30003"
  ).map(RedisURI.create)

  implicit val cts: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO]      = IO.timer(ExecutionContext.global)
  implicit val clock: Clock[IO]      = timer.clock

  private val stringCodec = DefaultRedisCodec(StringCodec.UTF8)

  private var dockerInstanceId: Option[String] = None

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    if (startContainers) {
      assertDockerAvailable()
      downloadRedisImage(dockerRedisClusterImage)
      dockerInstanceId = Some(startRedis(dockerRedisClusterImage, firstPort, Some(lastPort)))
    }
  }

  override protected def afterEach(): Unit = {
    flushAll()
    super.afterEach()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    dockerInstanceId.foreach(stopRedis(_, clearContainers))
  }

  private def mkRedisCluster[K, V](codec: Fs2RedisCodec[K, V]) =
    Fs2RedisClusterClient[IO](redisUri: _*)
      .flatMap { client =>
        Fs2Redis.cluster[IO, K, V](client, codec, redisUri: _*)
      }

  def withAbstractRedisCluster[A, K, V](
      f: Fs2Redis.RedisCommands[IO, K, V] => IO[A]
  )(codec: Fs2RedisCodec[K, V]): Unit =
    mkRedisCluster(codec).use(f).void.unsafeRunSync()

  def withRedisCluster[A](f: Fs2Redis.RedisCommands[IO, String, String] => IO[A]): Unit =
    withAbstractRedisCluster[A, String, String](f)(stringCodec)

  private def flushAll(): Unit =
    withRedisCluster {
      _.flushAll *> IO(println(">>>>>> CLUSTER FLUSHALL done <<<<<<<"))
    }
}
