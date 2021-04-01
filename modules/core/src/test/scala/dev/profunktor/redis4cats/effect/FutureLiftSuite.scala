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

package dev.profunktor.redis4cats.effect

import java.util.concurrent.CompletableFuture
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import munit.FunSuite

class FutureLiftSuite extends FunSuite {
  implicit val ioRuntime: IORuntime = cats.effect.unsafe.IORuntime.global

  val currentThread: IO[String] = IO(Thread.currentThread().getName)

  val instance = FutureLift[IO]

  test("it shifts back once the Future is converted") {
    val ioa =
      RedisExecutor.make[IO].use { implicit ec =>
        instance.liftCompletableFuture[String] {
          IO {
            val jFuture = new CompletableFuture[String]()
            jFuture.complete("foo")
            jFuture
          }
        }
      }

    (ioa *> currentThread)
      .flatMap(t => IO(assert(t.contains("io-compute"))))
      .unsafeToFuture()
  }

  test("it shifts back even when the CompletableFuture fails") {
    val ioa =
      RedisExecutor.make[IO].use { implicit ec =>
        instance.liftCompletableFuture[String] {
          IO {
            val jFuture = new CompletableFuture[String]()
            jFuture.completeExceptionally(new RuntimeException("Purposely fail"))
            jFuture
          }
        }
      }

    (ioa.attempt *> currentThread)
      .flatMap(t => IO(assert(t.contains("io-compute"))))
      .unsafeToFuture()
  }

}
