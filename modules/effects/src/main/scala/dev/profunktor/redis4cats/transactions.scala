/*
 * Copyright 2018-2019 ProfunKtor
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
import cats.effect.concurrent.Ref
import cats.effect.implicits._
import cats.implicits._
import dev.profunktor.redis4cats.algebra._
import dev.profunktor.redis4cats.effect.Log

object transactions {

  case class RedisTransaction[F[_]: Concurrent: Log, K, V](
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
    def run(commands: F[Any]*): F[Unit] =
      Ref.of[F, List[Fiber[F, Any]]](List.empty).flatMap { fibers =>
        Ref.of[F, Boolean](false).flatMap { txFailed =>
          val tx =
            Resource.makeCase(cmd.multi) {
              case (_, ExitCase.Completed) =>
                cmd.exec *> Log[F].info("Transaction completed")
              case (_, ExitCase.Error(e)) =>
                cmd.discard.guarantee(txFailed.set(true)) *> Log[F].error(s"Transaction failed: ${e.getMessage}")
              case (_, ExitCase.Canceled) =>
                cmd.discard.guarantee(txFailed.set(true)) *> Log[F].error("Transaction canceled")
            }

          val joinOrCancelFibers =
            fibers.get.flatMap { fbs =>
              txFailed.get.ifA(
                fbs.traverse(_.cancel).void,
                fbs.traverse(_.join).void
              )
            }

          Log[F].info("Transaction started") *>
            tx.use(_ => commands.toList.traverse(_.start).flatMap(fibers.set))
              .guarantee(joinOrCancelFibers)
        }
      }
  }

}
