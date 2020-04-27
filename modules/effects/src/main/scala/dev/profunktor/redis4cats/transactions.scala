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
    def run(commands: F[Any]*): F[List[Any]] =
      Ref.of[F, List[Fiber[F, Any]]](List.empty).flatMap { fibers =>
        Deferred[F, Either[Throwable, List[Any]]].flatMap { res =>
          val cancelFibers =
            fibers.get.flatMap(_.traverse(_.cancel).void)

          val joinFibers =
            fibers.get.flatMap(_.traverse(_.join))

          val tx =
            Resource.makeCase(cmd.multi) {
              case (_, ExitCase.Completed) =>
                F.info("Transaction completed") >>
                    cmd.exec >> joinFibers.flatMap(tr => res.complete(tr.asRight))
              case (_, ExitCase.Error(e)) =>
                F.error(s"Transaction failed: ${e.getMessage}") >>
                    cmd.discard.guarantee(cancelFibers >> res.complete(TransactionAborted.asLeft))
              case (_, ExitCase.Canceled) =>
                F.error("Transaction canceled") >>
                    cmd.discard.guarantee(res.complete(TransactionAborted.asLeft) >> cancelFibers)
            }

          // This guarantees that the commands run in order
          val runCommands = commands.toList.traverse_(_.start.flatMap(fb => fibers.update(_ :+ fb)))

          F.info("Transaction started") >>
            (tx.use(_ => runCommands) >> res.get.rethrow).timeout(3.seconds)
        }
      }
  }

}
