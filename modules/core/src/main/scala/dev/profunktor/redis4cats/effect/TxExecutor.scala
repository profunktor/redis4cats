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

/*
 * This file contains code adapted from cats-effect, which is
 * Copyright (c) 2017-2021 The Typelevel Cats-effect Project Developers.
 * The license notice for cats-effect is the same as the above.
 */

package dev.profunktor.redis4cats.effect

import java.util.concurrent.{ Executors, ThreadPoolExecutor, TimeUnit }

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
      .make(Sync[F].delay(Executors.newFixedThreadPool(1, TxThreadFactory).asInstanceOf[ThreadPoolExecutor])) { ec =>
        // shutdownNow()'s returned list is only tasks that were queued but never started - per its own
        // contract, a task already running is interrupted, not returned, so checking that list alone
        // misses a command that was actively executing (and forcibly cut off) at shutdown time. A separate
        // getActiveCount() snapshot doesn't fix this reliably either: reading it just before shutdownNow()
        // races the pool's single worker finishing its last task naturally in that same window, which would
        // misreport a genuinely clean shutdown as having outstanding work. Waiting briefly for the pool to
        // actually terminate after the interrupt is a direct, race-free signal instead: a task that merely
        // needed a moment to notice the interrupt and unwind terminates well within the grace period, while
        // one that's truly stuck (e.g. blocked on uninterruptible I/O) does not.
        Async[F].blocking(ec.shutdownNow()).flatMap { queued =>
          Async[F].blocking(ec.awaitTermination(500, TimeUnit.MILLISECONDS)).flatMap { terminated =>
            new IllegalStateException("There were outstanding tasks at time of shutdown of the Redis thread")
              .raiseError[F, Unit]
              .unlessA(queued.isEmpty && terminated)
          }
        }
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
