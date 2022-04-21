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

import cats.ApplicativeThrow
import cats.effect.kernel.Async
import cats.syntax.all._
import io.lettuce.core.{ ConnectionFuture, RedisFuture }

import java.util.concurrent._

private[redis4cats] trait FutureLift[F[_]] {
  def delay[A](thunk: => A): F[A]
  def lift[A](fa: => RedisFuture[A]): F[A]
  def liftConnectionFuture[A](fa: => ConnectionFuture[A]): F[A]
  def liftCompletableFuture[A](fa: => CompletableFuture[A]): F[A]
}

object FutureLift {
  private[redis4cats] type JFuture[A] = CompletionStage[A] with Future[A]

  def apply[F[_]: FutureLift]: FutureLift[F] = implicitly

  implicit def forAsync[F[_]: Async]: FutureLift[F] =
    new FutureLift[F] {
      val F = Async[F]

      def delay[A](thunk: => A): F[A] = F.delay(thunk)

      def lift[A](fa: => RedisFuture[A]): F[A] =
        liftJFuture[RedisFuture[A], A](fa)

      def liftConnectionFuture[A](fa: => ConnectionFuture[A]): F[A] =
        liftJFuture[ConnectionFuture[A], A](fa)

      def liftCompletableFuture[A](fa: => CompletableFuture[A]): F[A] =
        liftJFuture[CompletableFuture[A], A](fa)

      private[redis4cats] def liftJFuture[G <: JFuture[A], A](f: => G): F[A] =
        F.async { cb =>
          F.delay {
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
            }
            .as(Some(F.delay(f.cancel(true)).void))
        }
    }

  implicit final class FutureLiftOps[F[_]: ApplicativeThrow: FutureLift: Log, A](fa: => RedisFuture[A]) {
    def futureLift: F[A] =
      FutureLift[F].lift(fa).onError {
        case e: ExecutionException => Log[F].error(s"${e.getMessage()} - ${Option(e.getCause())}")
      }
  }

}
