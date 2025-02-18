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

/*
 * This file contains code adapted from cats-effect, which is
 * Copyright (c) 2017-2021 The Typelevel Cats-effect Project Developers.
 * The license notice for cats-effect is the same as the above.
 */

package dev.profunktor.redis4cats.effect

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import cats.effect.kernel._
import cats.syntax.all._

private[redis4cats] trait TxExecutor[F[_]] {
  def delay[A](thunk: => A): F[A]
  def eval[A](fa: F[A]): F[A]
  def start[A](fa: F[A]): F[Fiber[F, Throwable, A]]
  def liftK[G[_]: Async]: TxExecutor[G]
}

private[redis4cats] object TxExecutor {
  def make[F[_]: Async]: Resource[F, TxExecutor[F]] =
    Resource
      .make(Sync[F].delay(Executors.newFixedThreadPool(1, TxThreadFactory))) { ec =>
        Sync[F]
          .delay(ec.shutdownNow())
          .ensure(new IllegalStateException("There were outstanding tasks at time of shutdown of the Redis thread"))(
            _.isEmpty
          )
          .void
      }
      .map(es => fromEC(exitOnFatal(ExecutionContext.fromExecutorService(es))))

  private def exitOnFatal(ec: ExecutionContext): ExecutionContext = new ExecutionContext {
    def execute(r: Runnable): Unit =
      ec.execute(() =>
        try
          r.run()
        catch {
          case NonFatal(t) =>
            reportFailure(t)

          case t: Throwable =>
            // under most circumstances, this will work even with fatal errors
            t.printStackTrace()
            System.exit(1)
        }
      )

    def reportFailure(t: Throwable): Unit =
      ec.reportFailure(t)
  }

  private def fromEC[F[_]: Async](ec: ExecutionContext): TxExecutor[F] =
    new TxExecutor[F] {
      def delay[A](thunk: => A): F[A]                   = eval(Sync[F].delay(thunk))
      def eval[A](fa: F[A]): F[A]                       = Async[F].evalOn(fa, ec)
      def start[A](fa: F[A]): F[Fiber[F, Throwable, A]] = Async[F].startOn(fa, ec)
      def liftK[G[_]: Async]: TxExecutor[G]             = fromEC[G](ec)
    }
}
