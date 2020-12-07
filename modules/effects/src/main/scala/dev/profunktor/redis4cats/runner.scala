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

package dev.profunktor.redis4cats

import cats.effect._
import cats.effect.concurrent.Deferred
import cats.effect.implicits._
import cats.syntax.all._
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.hlist._
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

object Runner {
  type CancelFibers[F[_]] = Throwable => F[Unit]

  case class Ops[F[_]](
      name: String,
      mainCmd: F[Unit],
      onComplete: CancelFibers[F] => F[Unit],
      onError: F[Unit],
      afterCompletion: F[Unit],
      mkError: () => Throwable
  )

  def apply[F[_]: Concurrent: Log: Timer]: RunnerPartiallyApplied[F] =
    new RunnerPartiallyApplied[F]
}

private[redis4cats] class RunnerPartiallyApplied[F[_]: Concurrent: Log: Timer] {

  def filterExec[T <: HList, R <: HList, S <: HList](ops: Runner.Ops[F])(commands: T)(
      implicit w: Witness.Aux[T, R],
      f: Filter.Aux[R, S]
  ): F[S] = exec[T, R](ops)(commands).map(_.filterUnit)

  /**
    * This is unfortunately the easiest way to get optimistic locking to work in a deterministic way. Details follows.
    *
    * Every transactional command is forked, yielding a Fiber that is part of an HList. Modeling the transaction as
    * a `Resource`, we spawn all the fibers representing the commands and simulate a bit of delay to let the underlying
    * `ExecutionContext` schedule them before be proceed with the finalizer of the transaction, which always means
    * calling EXEC in a successful transaction.
    *
    * Without this tiny delay, we sometimes get into a race condition where not all the fibers have been scheduled
    * (meaning they haven't yet reached Redis), and where EXEC reaches the server before all the commands, making
    * the transaction result successful but not always deterministic.
    *
    * The `OptimisticLockSuite` tests the determinism of this implementation.
    *
    * A proper way to implement this might be to manage our own `ExecutionContext` so we could tell exactly when all the
    * fibers have been scheduled, and only then trigger the EXEC command. This would change the API, though, and it is
    * not as easy as it sounds but we can try and experiment with this in the future, if the time allows it.
    */
  private def getTxDelay: F[FiniteDuration] =
    F.delay {
      Duration(sys.env.getOrElse("REDIS4CATS_TX_DELAY", "50.millis")) match {
        case fd: FiniteDuration => fd
        case x                  => FiniteDuration(x.toMillis, TimeUnit.MILLISECONDS)
      }
    }

  def exec[T <: HList, R <: HList](ops: Runner.Ops[F])(commands: T)(implicit w: Witness.Aux[T, R]): F[R] =
    (Deferred[F, Either[Throwable, w.R]], F.delay(UUID.randomUUID), getTxDelay).tupled.flatMap {
      case (promise, uuid, txDelay) =>
        def cancelFibers[A](fibs: HList)(after: F[Unit])(err: Throwable): F[Unit] =
          joinOrCancel(fibs, HNil)(false).void.guarantee(after) >> promise.complete(err.asLeft)

        def onErrorOrCancelation(fibs: HList)(e: Throwable = ops.mkError()): F[Unit] =
          cancelFibers(fibs)(ops.onError)(e)

        F.debug(s"${ops.name} started - ID: $uuid") >>
          Resource
            .makeCase(ops.mainCmd >> runner(commands, HNil)) {
              case (fibs, ExitCase.Completed) =>
                for {
                  _ <- F.debug(s"${ops.name} completed - ID: $uuid")
                  _ <- ops.onComplete(onErrorOrCancelation(fibs))
                  tr <- joinOrCancel(fibs, HNil)(true)
                  // Casting here is fine since we have a `Witness` that proves this true
                  _ <- promise.complete(tr.asInstanceOf[w.R].asRight)
                } yield ()
              case (fibs, ExitCase.Error(e)) =>
                F.error(s"${ops.name} failed: ${e.getMessage} - ID: $uuid") >>
                    onErrorOrCancelation(fibs)()
              case (fibs, ExitCase.Canceled) =>
                F.error(s"${ops.name} canceled - ID: $uuid") >>
                    onErrorOrCancelation(fibs)()
            }
            .use(_ => F.sleep(txDelay).void)
            .guarantee(ops.afterCompletion) >> promise.get.rethrow.timeout(3.seconds)
    }

  // Forks every command in order
  private def runner[H <: HList, G <: HList](ys: H, res: G): F[HList] =
    ys match {
      case HNil                           => F.pure(res)
      case HCons((h: F[_] @unchecked), t) => h.start.flatMap(fb => runner(t, fb :: res))
    }

  // Joins or cancel fibers correspondent to previous executed commands
  private def joinOrCancel[H <: HList, G <: HList](ys: H, res: G)(isJoin: Boolean): F[HList] =
    ys match {
      case HNil => F.pure(res)
      case HCons((h: Fiber[F, Any] @unchecked), t) if isJoin =>
        h.join.flatMap(x => joinOrCancel(t, x :: res)(isJoin))
      case HCons((h: Fiber[F, Any] @unchecked), t) =>
        h.cancel.flatMap(x => joinOrCancel(t, x :: res)(isJoin))
      case HCons(h, t) =>
        F.error(s"Unexpected result: ${h.toString}") >> joinOrCancel(t, res)(isJoin)
    }

}
