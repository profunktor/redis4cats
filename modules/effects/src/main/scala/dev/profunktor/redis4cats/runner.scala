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
import cats.implicits._
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.hlist._

class HListRunner[F[_]: Concurrent: Log] {

  // Forks every command in order
  def runner[H <: HList, G <: HList](ys: H, res: G): F[Any] =
    ys match {
      case HNil                           => F.pure(res)
      case HCons((h: F[_] @unchecked), t) => h.start.flatMap(fb => runner(t, fb :: res))
    }

  // Joins or cancel fibers correspondent to previous executed commands
  def joinOrCancel[H <: HList, G <: HList](ys: H, res: G)(isJoin: Boolean): F[Any] =
    ys match {
      case HNil => F.pure(res)
      case HCons((h: Fiber[F, Any] @unchecked), t) if isJoin =>
        h.join.flatMap(x => joinOrCancel(t, x :: res)(isJoin))
      case HCons((h: Fiber[F, Any] @unchecked), t) =>
        h.cancel.flatMap(x => joinOrCancel(t, x :: res)(isJoin))
      case HCons(h, t) =>
        F.error(s"Unexpected result: ${h.toString}") >> joinOrCancel(t, res)(isJoin)
    }

  def cancelFibers[A](fibs: HList, err: Throwable, promise: Deferred[F, Either[Throwable, A]]): F[Unit] =
    joinOrCancel(fibs, HNil)(false).void >> promise.complete(err.asLeft)

}
