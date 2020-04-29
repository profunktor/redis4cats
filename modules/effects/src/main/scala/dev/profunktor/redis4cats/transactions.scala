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
import cats.effect.concurrent._
import cats.effect.implicits._
import cats.implicits._
import dev.profunktor.redis4cats.algebra._
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.hlist._
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object transactions {

  case object TransactionAborted extends NoStackTrace

  case class RedisTransaction[F[_]: Concurrent: Log: Timer, K, V](
      cmd: RedisCommands[F, K, V]
  ) {

    /***
      * Exclusively run Redis commands as part of a transaction.
      *
      * Every command needs to be forked (`.start`) to be sent to the server asynchronously.
      * After a transaction is complete, either successfully or with a failure, the spawned
      * fibers will be treated accordingly.
      *
      * It should not be used to run other computations, only Redis commands. Fail to do so
      * may end in unexpected results such as a dead lock.
      */
    def exec[T <: HList, R <: HList](xs: T)(implicit w: Witness.Aux[T, R]): F[R] =
      Deferred[F, Either[Throwable, w.R]].flatMap { promise =>
        // TODO: All these functions follow the same pattern, extract out
        def runner[H <: HList, G <: HList](ys: H, res: G): F[Any] =
          ys match {
            case HNil                           => F.pure(res)
            case HCons((h: F[_] @unchecked), t) => h.start.flatMap(fb => runner(t, fb :: res))
          }

        def joiner[H <: HList, G <: HList](ys: H, res: G): F[Any] =
          ys match {
            case HNil => F.pure(res)
            case HCons((h: Fiber[F, Any] @unchecked), t) =>
              h.join.flatMap(x => F.pure(println(x)) >> joiner(t, x :: res))
          }

        def canceler[H <: HList, G <: HList](ys: H, res: G): F[Any] =
          ys.reverse.asInstanceOf[H] match {
            case HNil                                    => F.pure(res)
            case HCons((h: Fiber[F, Any] @unchecked), t) => h.cancel.flatMap(x => canceler(t, x :: res))
          }

        val tx =
          Resource.makeCase(cmd.multi >> runner(xs, HNil)) {
            case ((fibs: HList), ExitCase.Completed) =>
              F.info("Transaction completed") >>
                  cmd.exec.guarantee(joiner(fibs, HNil).flatMap(tr => promise.complete(tr.asInstanceOf[w.R].asRight)))
            case ((fibs: HList), ExitCase.Error(e)) =>
              F.error(s"Transaction failed: ${e.getMessage}") >>
                  cmd.discard.guarantee(canceler(fibs, HNil) >> promise.complete(TransactionAborted.asLeft))
            case ((fibs: HList), ExitCase.Canceled) =>
              F.error("Transaction canceled") >>
                  cmd.discard.guarantee(canceler(fibs, HNil) >> promise.complete(TransactionAborted.asLeft))
            case _ => F.error("Kernel panic: the impossible happened!")
          }

        F.info("Transaction started") >>
          (tx.use(_ => F.unit) >> promise.get.rethrow).timeout(3.seconds)
      }

  }

}
