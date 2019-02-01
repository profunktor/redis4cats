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
import com.github.gvolpe.fs2redis.connection.Fs2RedisClient
import com.github.gvolpe.fs2redis.domain.{ DefaultRedisCodec, Fs2RedisCodec }
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.StringCodec
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, Suite }

import scala.concurrent.{ ExecutionContext, SyncVar }
import scala.sys.process.{ Process, ProcessLogger }
import scala.util.Random

// Highly-inspired by DockerCassandra -> https://github.com/Spinoco/fs2-cassandra/blob/series/0.4/test-support/src/main/scala/spinoco/fs2/cassandra/support/DockerCassandra.scala
trait DockerRedis extends BeforeAndAfterAll with BeforeAndAfterEach { self: Suite =>
  import DockerRedis._, testLogger._

  // override this if the Redis container has to be started before invocation
  // when developing tests, this likely shall be false, so there is no additional overhead starting Redis
  lazy val startContainers: Boolean = true

  // override this to indicate whether containers shall be removed (true) once the test with Redis is done.
  lazy val clearContainers: Boolean = true

  lazy val redisPort: Int = 6379

  lazy val redisUri: RedisURI = RedisURI.create("redis://localhost")

  private var dockerInstanceId: Option[String] = None

  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO]     = IO.timer(ExecutionContext.global)
  implicit val clock: Clock[IO]     = timer.clock

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    if (startContainers) {
      assertDockerAvailable()
      downloadRedisImage(dockerRedisImage)
      dockerInstanceId = Some(startRedis(dockerRedisImage, redisPort))
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

  private val stringCodec = DefaultRedisCodec(StringCodec.UTF8)

  private def mkRedis[K, V](codec: Fs2RedisCodec[K, V]) =
    Fs2RedisClient[IO](redisUri)
      .flatMap { client =>
        Fs2Redis[IO, K, V](client, codec, redisUri)
      }

  def withAbstractRedis[A, K, V](f: Fs2Redis.RedisCommands[IO, K, V] => IO[A])(codec: Fs2RedisCodec[K, V]): Unit =
    mkRedis(codec).use(f).void.unsafeRunSync()

  def withRedis[A](f: Fs2Redis.RedisCommands[IO, String, String] => IO[A]): Unit =
    withAbstractRedis[A, String, String](f)(stringCodec)

  private def flushAll(): Unit =
    withRedis {
      _.flushAll *> IO(println(">>>>>> FLUSHALL done <<<<<<<"))
    }
}

object DockerRedis {

  val dockerRedisImage        = "redis:5.0.0"
  val dockerRedisClusterImage = "m0stwanted/redis-cluster:latest"

  /** asserts that docker is available on host os **/
  def assertDockerAvailable(): Unit = {
    val r = Process("docker -v").!!
    println(s"Verifying docker is available: $r")
  }

  def downloadRedisImage(image: String): Unit = {
    val current: String = Process(s"docker images $image").!!
    if (current.linesIterator.size <= 1) {
      println(s"Pulling docker image for $image")
      Process(s"docker pull $image").!!
      ()
    }
  }

  def startRedis(image: String, firstPort: Int, lastPort: Option[Int] = None): String = {

    val dockerId = new SyncVar[String]()
    val ports    = lastPort.map(lp => s"$firstPort-$lp:$firstPort-$lp").getOrElse(s"$firstPort:$firstPort")

    val runCmd =
      s"docker run --name scalatest_redis_${System.currentTimeMillis()}_${math.abs(Random.nextInt)} -d -p $ports $image"

    val thread = new Thread(
      new Runnable {
        def run(): Unit = {
          val result                    = Process(runCmd).!!.trim
          var observer: Option[Process] = None
          val logger = ProcessLogger(
            { str =>
              if (str.contains("Ready to accept connections") || str
                    .contains("Background AOF rewrite finished successfully")) {
                observer.foreach(_.destroy())
                dockerId.put(result)
              }
            },
            _ => ()
          )

          println(s"Awaiting Redis startup ($image @ 127.0.0.1:($ports))")
          val observeCmd = s"docker logs -f $result"
          observer = Some(Process(observeCmd).run(logger))
        }
      },
      s"Redis $image startup observer"
    )
    thread.start()
    val id = dockerId.get
    println(s"Redis ($image @ 127.0.0.1:($ports)) started successfully as $id ")
    id
  }

  def stopRedis(instance: String, clearContainer: Boolean): Unit =
    if (clearContainer) {
      val killCmd = s"docker kill $instance"
      Process(killCmd).!!
      val rmCmd = s"docker rm $instance"
      Process(rmCmd).!!
      ()
    }

}
