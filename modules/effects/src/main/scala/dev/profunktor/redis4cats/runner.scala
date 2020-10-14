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

  def exec[T <: HList, R <: HList](ops: Runner.Ops[F])(commands: T)(implicit w: Witness.Aux[T, R]): F[R] =
    (Deferred[F, Either[Throwable, w.R]], F.delay(UUID.randomUUID)).tupled.flatMap {
      case (promise, uuid) =>
        def cancelFibers[A](fibs: HList)(err: Throwable): F[Unit] =
          joinOrCancel(fibs, HNil)(false).void >> promise.complete(err.asLeft)

        F.debug(s"${ops.name} started - ID: $uuid") >>
          Resource
            .makeCase(ops.mainCmd >> runner(commands, HNil)) {
              case ((fibs: HList), ExitCase.Completed) =>
                for {
                  _ <- F.debug(s"${ops.name} completed - ID: $uuid")
                  _ <- ops.onComplete(cancelFibers(fibs))
                  tr <- joinOrCancel(fibs, HNil)(true)
                  // Casting here is fine since we have a `Witness` that proves this true
                  _ <- promise.complete(tr.asInstanceOf[w.R].asRight)
                } yield ()
              case ((fibs: HList), ExitCase.Error(e)) =>
                F.error(s"${ops.name} failed: ${e.getMessage} - ID: $uuid") >>
                    ops.onError.guarantee(cancelFibers(fibs)(ops.mkError()))
              case ((fibs: HList), ExitCase.Canceled) =>
                F.error(s"${ops.name} canceled - ID: $uuid") >>
                    ops.onError.guarantee(cancelFibers(fibs)(ops.mkError()))
            }
            .use(_ => F.unit)
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
