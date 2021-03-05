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

import cats.effect._
import cats.effect.implicits._
import cats.syntax.all._
import io.lettuce.core.{ ConnectionFuture, RedisFuture }
import java.util.concurrent._

private[redis4cats] object JRFuture {

  private[redis4cats] type JFuture[A] = CompletionStage[A] with Future[A]

  def apply[F[_]: Concurrent: ContextShift: RedisExecutor, A](
      fa: F[RedisFuture[A]]
  ): F[A] =
    liftJFuture[F, RedisFuture[A], A](fa)

  def fromConnectionFuture[F[_]: Concurrent: ContextShift: RedisExecutor, A](
      fa: F[ConnectionFuture[A]]
  ): F[A] =
    liftJFuture[F, ConnectionFuture[A], A](fa)

  def fromCompletableFuture[F[_]: Concurrent: ContextShift: RedisExecutor, A](
      fa: F[CompletableFuture[A]]
  ): F[A] =
    liftJFuture[F, CompletableFuture[A], A](fa)

  implicit final class FutureLiftOps[F[_]: Concurrent: ContextShift: RedisExecutor: Log, A](fa: F[RedisFuture[A]]) {
    def futureLift: F[A] =
      liftJFuture[F, RedisFuture[A], A](fa).onError {
        case e: ExecutionException => F.error(s"${e.getMessage()} - ${Option(e.getCause())}")
      }
  }

  private[redis4cats] def liftJFuture[
      F[_]: Concurrent: ContextShift: RedisExecutor,
      G <: JFuture[A],
      A
  ](fa: F[G]): F[A] = {
    val lifted: F[A] = RedisExecutor[F].eval {
      fa.flatMap { f =>
        RedisExecutor[F].eval {
          F.cancelable { cb =>
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
            RedisExecutor[F].delay(f.cancel(true)).void
          }
        }
      }
    }
    lifted.guarantee(F.shift)
  }

}
