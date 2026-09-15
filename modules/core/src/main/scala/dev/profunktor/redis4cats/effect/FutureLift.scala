/*
 * Copyright 2018-2025 ProfunKtor
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
import cats.effect.kernel.syntax.monadCancel._
import cats.syntax.all._
import io.lettuce.core.RedisFuture

import java.util.concurrent._

private[redis4cats] trait FutureLift[F[_]] {
  def delay[A](thunk: => A): F[A]
  def blocking[A](thunk: => A): F[A]
  def guarantee[A](fa: F[A], fu: F[Unit]): F[A]
  def lift[A](fa: => FutureLift.JFuture[A]): F[A]
}

object FutureLift {
  private[redis4cats] type JFuture[A] = CompletionStage[A] with Future[A]

  def apply[F[_]: FutureLift]: FutureLift[F] = implicitly

  implicit def forAsync[F[_]: Async]: FutureLift[F] =
    new FutureLift[F] {
      val F = Async[F]

      def delay[A](thunk: => A): F[A] = F.delay(thunk)

      def blocking[A](thunk: => A): F[A] = F.blocking(thunk)

      def guarantee[A](fa: F[A], fu: F[Unit]): F[A] = fa.guarantee(fu)

      def lift[A](fa: => JFuture[A]): F[A] =
        F.fromCompletionStage(F.delay(fa))
    }

  implicit final class FutureLiftOps[F[_]: ApplicativeThrow: FutureLift: Log, A](fa: => RedisFuture[A]) {
    def futureLift: F[A] =
      FutureLift[F].lift(fa).onError { case e: ExecutionException =>
        // onError re-raises the original error only after this action succeeds - if the (user-supplied,
        // swappable) Log[F] instance itself throws, that failure would otherwise replace the real Redis
        // error instead of just failing to log it.
        Log[F].error(s"${e.getMessage()} - ${Option(e.getCause())}").attempt.void
      }
  }

}
