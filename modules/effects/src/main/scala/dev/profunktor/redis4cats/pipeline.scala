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
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.hlist._
import scala.util.control.NoStackTrace

object pipeline {

  case object PipelineError extends NoStackTrace

  case class RedisPipeline[F[_]: Concurrent: Log: Timer, K, V](
      cmd: RedisCommands[F, K, V]
  ) {

    def exec[T <: HList, R <: HList](commands: T)(implicit w: Witness.Aux[T, R]): F[R] =
      Runner[F].exec(
        Runner.Ops(
          name = "Pipeline",
          mainCmd = cmd.disableAutoFlush,
          onComplete = (_: Runner.CancelFibers[F]) => cmd.flushCommands,
          onError = F.unit,
          afterCompletion = cmd.enableAutoFlush,
          mkError = () => PipelineError
        )
      )(commands)

  }

}
