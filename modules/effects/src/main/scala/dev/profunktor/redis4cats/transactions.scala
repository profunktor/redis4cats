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
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.hlist._
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object transactions {

  sealed trait TransactionError extends NoStackTrace
  case object TransactionAborted extends TransactionError
  case object TransactionDiscarded extends TransactionError

  case class RedisTransaction[F[_]: Concurrent: Log: Timer, K, V](
      cmd: RedisCommands[F, K, V]
  ) extends HListRunner[F] {

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
    def exec[T <: HList, R <: HList](commands: T)(implicit w: Witness.Aux[T, R]): F[R] =
      Deferred[F, Either[Throwable, w.R]].flatMap { promise =>
        F.info("Transaction started") >>
          Resource
            .makeCase(cmd.multi >> runner(commands, HNil)) {
              case ((fibs: HList), ExitCase.Completed) =>
                for {
                  _ <- F.info("Transaction completed")
                  _ <- cmd.exec.handleErrorWith(e => cancelFibers(fibs, e, promise) >> F.raiseError(e))
                  tr <- joinOrCancel(fibs, HNil)(true)
                  // Casting here is fine since we have a `Witness` that proves this true
                  _ <- promise.complete(tr.asInstanceOf[w.R].asRight)
                } yield ()
              case ((fibs: HList), ExitCase.Error(e)) =>
                F.error(s"Transaction failed: ${e.getMessage}") >>
                    cmd.discard.guarantee(cancelFibers(fibs, TransactionAborted, promise))
              case ((fibs: HList), ExitCase.Canceled) =>
                F.error("Transaction canceled") >>
                    cmd.discard.guarantee(cancelFibers(fibs, TransactionAborted, promise))
              case _ =>
                F.error("Kernel panic: the impossible happened!")
            }
            .use(_ => F.unit) >> promise.get.rethrow.timeout(3.seconds)
      }

  }

}
