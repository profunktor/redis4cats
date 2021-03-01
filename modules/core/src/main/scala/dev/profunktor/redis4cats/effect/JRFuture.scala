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

trait RedisEc {
  def delay[F[_]: Sync: ContextShift, A](thunk: => A): F[A]
  def eval[F[_]: ContextShift, A](fa: F[A]): F[A]
}

object RedisEc {
  private def apply(blocker: Blocker): RedisEc =
    new RedisEc {
      override def delay[F[_]: Sync: ContextShift, A](thunk: => A): F[A] = blocker.delay(thunk)
      override def eval[F[_]: ContextShift, A](fa: F[A]): F[A]           = blocker.blockOn(fa)
    }

  def apply[F[_]: Sync]: Resource[F, RedisEc] =
    Blocker.fromExecutorService(F.delay(Executors.newFixedThreadPool(1))).map(RedisEc.apply)
}

object JRFuture {

  private[redis4cats] type JFuture[A] = CompletionStage[A] with Future[A]

  def apply[F[_]: Concurrent: ContextShift, A](
      fa: F[RedisFuture[A]]
  )(redisEc: RedisEc): F[A] =
    liftJFuture[F, RedisFuture[A], A](fa)(redisEc)

  def fromConnectionFuture[F[_]: Concurrent: ContextShift, A](
      fa: F[ConnectionFuture[A]]
  )(redisEc: RedisEc): F[A] =
    liftJFuture[F, ConnectionFuture[A], A](fa)(redisEc)

  def fromCompletableFuture[F[_]: Concurrent: ContextShift, A](
      fa: F[CompletableFuture[A]]
  )(redisEc: RedisEc): F[A] =
    liftJFuture[F, CompletableFuture[A], A](fa)(redisEc)

  implicit class FutureLiftOps[F[_]: Concurrent: ContextShift: Log, A](fa: F[RedisFuture[A]]) {
    def futureLift(implicit rb: RedisEc): F[A] =
      liftJFuture[F, RedisFuture[A], A](fa)(rb).onError {
        case e: ExecutionException => F.error(s"${e.getMessage()} - ${Option(e.getCause())}")
      }
  }

  private[redis4cats] def liftJFuture[
      F[_]: Concurrent: ContextShift,
      G <: JFuture[A],
      A
  ](fa: F[G])(redisEc: RedisEc): F[A] = {
    val lifted: F[A] = redisEc.eval {
      fa.flatMap { f =>
        redisEc.eval {
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
            redisEc.delay(f.cancel(true)).void
          }
        }
      }
    }
    lifted.guarantee(F.shift)
  }

}
