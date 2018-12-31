/*
 * Copyright 2018-2019 Fs2 Redis
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

package com.github.gvolpe.fs2redis.effect

import java.util.concurrent.CompletableFuture

import cats.effect.{ ContextShift, IO }
import cats.syntax.all._
import org.scalatest.AsyncFunSuite

class JRFutureSpec extends AsyncFunSuite {

  implicit val cs: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)

  val currentThread: IO[String] = IO(Thread.currentThread().getName)

  test("it shifts back once the Future is converted") {
    val ioa =
      JRFuture.fromCompletableFuture[IO, String] {
        IO {
          val jFuture = new CompletableFuture[String]()
          jFuture.complete("foo")
          jFuture
        }
      }

    (ioa *> currentThread)
      .flatMap { t =>
        IO(assert(t.contains("scala-execution-context-global")))
      }
      .unsafeToFuture()
  }

}
