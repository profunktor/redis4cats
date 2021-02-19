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

package dev.profunktor.redis4cats.effect

import cats.effect._
import cats.syntax.all._
import io.lettuce.core.{ ConnectionFuture, RedisFuture }
import java.util.concurrent._

object JRFuture {

  private[redis4cats] type JFuture[A] = CompletionStage[A] with Future[A]

  def apply[F[_]: Async, A](
      fa: F[RedisFuture[A]]
  ): F[A] =
    liftJFuture[F, RedisFuture[A], A](fa)

  def fromConnectionFuture[F[_]: Async, A](
      fa: F[ConnectionFuture[A]]
  ): F[A] =
    liftJFuture[F, ConnectionFuture[A], A](fa)

  def fromCompletableFuture[F[_]: Async, A](
      fa: F[CompletableFuture[A]]
  ): F[A] =
    liftJFuture[F, CompletableFuture[A], A](fa)

  implicit class FutureLiftOps[F[_]: Async: Log, A](fa: F[RedisFuture[A]]) {
    def futureLift: F[A] =
      liftJFuture[F, RedisFuture[A], A](fa).onError {
        case e: ExecutionException => F.error(s"${e.getMessage()} - ${Option(e.getCause())}")
      }
  }

  private[redis4cats] def liftJFuture[
      F[_]: Async,
      G <: JFuture[A],
      A
  ](fa: F[G]): F[A] =
    fa.flatMap { f =>
      F.async { cb =>
        f.handle[Unit] { (res: A, err: Throwable) =>
          err match {
            case null =>
              cb(Right(res))
            case _: CancellationException =>
              ()
            case ex: CompletionException if ex.getCause ne null =>
              cb(Left(ex.getCause))
            case ex =>
              cb(Left(ex))
          }
        }
        F.blocking(f.cancel(true)).as(None)
      }
    }

}
