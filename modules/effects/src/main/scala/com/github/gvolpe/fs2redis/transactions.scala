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

import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import com.github.gvolpe.fs2redis.algebra._
import com.github.gvolpe.fs2redis.effect.Log

object transactions {

  case class RedisTransaction[F[_]: Log: Sync, K, V, A](
      cmd: RedisCommands[F, K, V]
  ) {
    def run(fa: F[A]): F[A] =
      Log[F].info("Transaction started") *>
        cmd.multi.bracketCase(_ => fa) {
          case (_, ExitCase.Completed) => cmd.exec *> Log[F].info("Transaction completed")
          case (_, ExitCase.Error(e))  => cmd.discard *> Log[F].error(s"Transaction failed: ${e.getMessage}")
          case (_, ExitCase.Canceled)  => cmd.discard *> Log[F].error("Transaction canceled")
        }
  }

}
