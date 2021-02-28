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
import cats.effect.syntax.all._
import cats.syntax.all._
import io.lettuce.core.{ ConnectionFuture, RedisFuture }
import java.util.concurrent._
import scala.concurrent.ExecutionContext

trait RedisBlocker {
  def ec: ExecutionContext
}

object RedisBlocker {
  def apply(rec: ExecutionContext): RedisBlocker =
    new RedisBlocker {
      def ec: ExecutionContext = rec
    }
}
object JRFuture {

  private[redis4cats] type JFuture[A] = CompletionStage[A] with Future[A]

  private[redis4cats] def mkEc[F[_]: Sync]: Resource[F, ExecutionContext] =
    Resource.eval(F.delay(ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(1)))) // TODO: cleanup

  def apply[F[_]: Async, A](
      fa: F[RedisFuture[A]]
  )(ec: ExecutionContext): F[A] =
    liftJFuture[F, RedisFuture[A], A](fa)(ec)

  def fromConnectionFuture[F[_]: Async, A](
      fa: F[ConnectionFuture[A]]
  )(ec: ExecutionContext): F[A] =
    liftJFuture[F, ConnectionFuture[A], A](fa)(ec)

  def fromCompletableFuture[F[_]: Async, A](
      fa: F[CompletableFuture[A]]
  )(ec: ExecutionContext): F[A] =
    liftJFuture[F, CompletableFuture[A], A](fa)(ec)

  implicit class FutureLiftOps[F[_]: Async: Log, A](fa: F[RedisFuture[A]])(implicit redisEc: RedisBlocker) {
    def futureLift: F[A] =
      liftJFuture[F, RedisFuture[A], A](fa)(redisEc.ec).onError {
        case e: ExecutionException => F.error(s"${e.getMessage()} - ${Option(e.getCause())}")
      }
  }

  private[redis4cats] def liftJFuture[
      F[_]: Async,
      G <: JFuture[A],
      A
  ](fa: F[G])(ec: ExecutionContext): F[A] =
    fa.flatMap[A] { f =>
        F.async[A] { cb =>
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
            F.pure(Some(F.delay(f.cancel(true)).evalOn(ec).void))
          }
          .evalOn(ec)
      }
      .evalOn(ec)

}
