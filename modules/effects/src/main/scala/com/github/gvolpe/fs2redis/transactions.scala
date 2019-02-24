/*
 * Copyright 2018-2019 Gabriel Volpe
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

package com.github.gvolpe.fs2redis

import cats.implicits._
import cats.effect._
import cats.effect.implicits._
import com.github.gvolpe.fs2redis.effect.Log
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis._

object transactions {

  implicit class TxOps[F[_]: Bracket[?[_], Throwable]: Log, K, V](cmd: RedisCommands[F, K, V]) {

    def transactional(commands: F[Unit]): F[Unit] =
      Log[F].info("Transaction started") *>
        cmd.multi.bracketCase(_ => commands) {
          case (_, ExitCase.Completed) => cmd.exec *> Log[F].info("Transaction completed")
          case (_, ExitCase.Error(e))  => cmd.discard *> Log[F].error(s"Transaction failed: ${e.getMessage}")
          case (_, ExitCase.Canceled)  => cmd.discard *> Log[F].error("Transaction canceled")
        }

  }

}
