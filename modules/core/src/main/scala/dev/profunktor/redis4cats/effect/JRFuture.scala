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
import cats.effect.implicits._
import cats.syntax.all._
import io.lettuce.core.{ ConnectionFuture, RedisFuture }
import java.util.concurrent._

private[redis4cats] object JRFuture {

  private[redis4cats] type JFuture[A] = CompletionStage[A] with Future[A]

  def apply[F[_]: Concurrent: ContextShift, A](
      fa: F[RedisFuture[A]]
  )(implicit redisExecutor: RedisExecutor[F]): F[A] =
    liftJFuture[F, RedisFuture[A], A](fa)

  def fromConnectionFuture[F[_]: Concurrent: ContextShift, A](
      fa: F[ConnectionFuture[A]]
  )(implicit redisExecutor: RedisExecutor[F]): F[A] =
    liftJFuture[F, ConnectionFuture[A], A](fa)

  def fromCompletableFuture[F[_]: Concurrent: ContextShift, A](
      fa: F[CompletableFuture[A]]
  )(implicit redisExecutor: RedisExecutor[F]): F[A] =
    liftJFuture[F, CompletableFuture[A], A](fa)

  implicit final class FutureLiftOps[F[_]: Concurrent: ContextShift: Log, A](fa: F[RedisFuture[A]]) {
    def futureLift(implicit rb: RedisExecutor[F]): F[A] =
      liftJFuture[F, RedisFuture[A], A](fa).onError {
        case e: ExecutionException => F.error(s"${e.getMessage()} - ${Option(e.getCause())}")
      }
  }

  private[redis4cats] def liftJFuture[
      F[_]: Concurrent: ContextShift,
      G <: JFuture[A],
      A
  ](fa: F[G])(implicit redisExecutor: RedisExecutor[F]): F[A] = {
    val lifted: F[A] = redisExecutor.eval {
      fa.flatMap { f =>
        redisExecutor.eval {
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
            redisExecutor.delay(f.cancel(true)).void
          }
        }
      }
    }
    lifted.guarantee(F.shift)
  }

}
