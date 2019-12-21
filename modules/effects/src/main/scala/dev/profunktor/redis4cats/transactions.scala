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
import cats.effect.implicits._
import cats.implicits._
import dev.profunktor.redis4cats.algebra._
import dev.profunktor.redis4cats.effect.Log

object transactions {

  case class RedisTransaction[F[_]: Concurrent: Log, K, V](
      cmd: RedisCommands[F, K, V]
  ) {

    def run(instructions: F[Any]*)(strictFas: F[Any]*): F[Unit] = {

      val tx =
        Resource.makeCase(cmd.multi) {
          case (_, ExitCase.Completed) =>
            cmd.exec *> Log[F].info("Transaction completed")
          case (_, ExitCase.Error(e)) =>
            cmd.discard *> Log[F].error(s"Transaction failed: ${e.getMessage}")
          case (_, ExitCase.Canceled) =>
            cmd.discard *> Log[F].error("Transaction canceled")
        }

      val commands =
        Resource.makeCase(strictFas.toList.sequence_ >> instructions.toList.traverse(_.start)) {
          case (_, ExitCase.Completed) => ().pure[F]
          case (f, ExitCase.Error(_))  => f.traverse_(_.cancel)
          case (f, ExitCase.Canceled)  => f.traverse_(_.cancel)
        }

      Log[F].info("Transaction started") *>
        (tx >> commands).use(_.pure[F]).bracket(_.traverse_(_.join))(_.traverse_(_.cancel)).void
    }

  }

}
