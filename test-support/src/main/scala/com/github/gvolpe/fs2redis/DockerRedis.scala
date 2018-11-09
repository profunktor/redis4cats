/*
 * Copyright 2018 Fs2 Redis
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

import cats.effect.{ ContextShift, IO }
import cats.syntax.apply._
import cats.syntax.functor._
import com.github.gvolpe.fs2redis.connection.Fs2RedisClient
import com.github.gvolpe.fs2redis.domain.DefaultRedisCodec
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.StringCodec
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, Suite }

import scala.concurrent.{ ExecutionContext, SyncVar }
import scala.sys.process.{ Process, ProcessLogger }

// Highly-inspired by DockerCassandra -> https://github.com/Spinoco/fs2-cassandra/blob/series/0.4/test-support/src/main/scala/spinoco/fs2/cassandra/support/DockerCassandra.scala
trait DockerRedis extends BeforeAndAfterAll with BeforeAndAfterEach { self: Suite =>
  import DockerRedis._

  // override this if the Redis container has to be started before invocation
  // when developing tests, this likely shall be false, so there is no additional overhead starting Redis
  lazy val startContainers: Boolean = true

  // override this to indicate whether containers shall be removed (true) once the test with Redis is done.
  lazy val clearContainers: Boolean = true

  lazy val redisPort: Int = 6379

  lazy val redisUri: RedisURI = RedisURI.create("redis://localhost")

  private var dockerInstanceId: Option[String] = None

  implicit val cts: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    if (startContainers) {
      assertDockerAvailable()
      downloadRedisImage()
      dockerInstanceId = Some(startRedis(redisPort))
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

  private val mkRedis =
    Fs2RedisClient[IO](redisUri)
      .flatMap { client =>
        Fs2Redis[IO, String, String](client, stringCodec, redisUri)
      }

  def withRedis[A](f: Fs2Redis.RedisCommands[IO, String, String] => IO[A]): Unit =
    mkRedis.use(f).void.unsafeRunSync()

  private def flushAll(): Unit =
    Fs2RedisClient[IO](redisUri)
      .use { client =>
        IO {
          val conn = client.underlying.connect(redisUri)
          conn.sync().flushall()
        } *> IO(println(">>>>>> FLUSHALL done <<<<<<<"))
      }
      .void
      .unsafeRunSync()

}

object DockerRedis {

  val dockerImage = "redis:5.0.0"

  /** asserts that docker is available on host os **/
  def assertDockerAvailable(): Unit = {
    val r = Process("docker -v").!!
    println(s"Verifying docker is available: $r")
  }

  def downloadRedisImage(): Unit = {
    val current: String = Process(s"docker images $dockerImage").!!
    if (current.lines.size <= 1) {
      println(s"Pulling docker image for $dockerImage")
      Process(s"docker pull $dockerImage").!!
      ()
    }
  }

  def startRedis(port: Int): String = {

    val dockerId = new SyncVar[String]()
    val runCmd =
      s"docker run --name scalatest_redis_${System.currentTimeMillis()} -d -p $port:6379 $dockerImage"

    val thread = new Thread(
      new Runnable {
        def run(): Unit = {
          val result                    = Process(runCmd).!!.trim
          var observer: Option[Process] = None
          val logger = ProcessLogger(
            { str =>
              if (str.contains("Ready to accept connections")) {
                observer.foreach(_.destroy())
                dockerId.put(result)
              }
            },
            _ => ()
          )

          println(s"Awaiting Redis startup ($dockerImage @ 127.0.0.1:$port)")
          val observeCmd = s"docker logs -f $result"
          observer = Some(Process(observeCmd).run(logger))
        }
      },
      s"Redis $dockerImage startup observer"
    )
    thread.start()
    val id = dockerId.get
    println(s"Redis ($dockerImage @ 127.0.0.1:$port) started successfully as $id ")
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
