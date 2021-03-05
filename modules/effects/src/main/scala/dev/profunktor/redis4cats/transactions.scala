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

package dev.profunktor.redis4cats

import scala.util.control.NoStackTrace

import cats.Parallel
import cats.effect._
import cats.syntax.all._
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.hlist._

object transactions {

  sealed trait TransactionError extends NoStackTrace
  case object TransactionAborted extends TransactionError
  case object TransactionDiscarded extends TransactionError

  case class RedisTransaction[F[_]: Concurrent: Log: Parallel: Timer, K, V](
      cmd: RedisCommands[F, K, V]
  ) {

    private val ops =
      Runner.Ops(
        name = "Transaction",
        mainCmd = cmd.multi,
        onComplete = (f: Runner.CancelFibers[F]) => cmd.exec.handleErrorWith(e => f(e) >> F.raiseError(e)),
        onError = cmd.discard,
        afterCompletion = F.unit,
        mkError = () => TransactionAborted
      )

    /**
      * Same as @exec, except it filters out values of type Unit
      * from its result.
      */
    def filterExec[T <: HList, R <: HList, S <: HList](commands: T)(
        implicit w: Witness.Aux[T, R],
        f: Filter.Aux[R, S]
    ): F[S] = Runner[F].filterExec(ops)(commands)

    /***
      * Exclusively run Redis commands as part of a transaction.
      *
      * Every command needs to be forked (`.start`) to be sent to the server asynchronously.
      * After a transaction is complete, either successfully or with a failure, the spawned
      * fibers will be treated accordingly.
      *
      * It should not be used to run other computations, only Redis commands. Fail to do so
      * may end in unexpected results such as a dead lock.
      *
      * @return `F[R]` or it raises a @TransactionError in case of failure.
      */
    def exec[T <: HList, R <: HList](commands: T)(implicit w: Witness.Aux[T, R]): F[R] =
      Runner[F].exec(ops)(commands)

  }

}
