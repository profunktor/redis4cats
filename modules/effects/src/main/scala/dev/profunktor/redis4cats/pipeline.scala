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

object pipeline {

  case class RedisPipeline[F[_]: Log: Bracket[*[_], Throwable], K, V](
      cmd: RedisCommands[F, K, V]
  ) {
    def run[A](fa: F[A]): F[A] =
      F.info("Pipeline started") *>
        cmd.disableAutoFlush
          .bracketCase(_ => fa) {
            case (_, ExitCase.Completed) => cmd.flushCommands *> F.info("Pipeline completed")
            case (_, ExitCase.Error(e))  => F.error(s"Pipeline failed: ${e.getMessage}")
            case (_, ExitCase.Canceled)  => F.error("Pipeline canceled")
          }
          .guarantee(cmd.enableAutoFlush)
  }

}
