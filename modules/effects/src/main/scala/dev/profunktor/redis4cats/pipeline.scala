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
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.hlist._

object pipeline {

  case object PipelineError extends NoStackTrace

  case class RedisPipeline[F[_]: Concurrent: Log: Parallel: Timer, K, V](
      cmd: RedisCommands[F, K, V]
  ) {

    private val ops =
      Runner.Ops(
        name = "Pipeline",
        mainCmd = cmd.disableAutoFlush,
        onComplete = (_: Runner.CancelFibers[F]) => cmd.flushCommands,
        onError = F.unit,
        afterCompletion = cmd.enableAutoFlush,
        mkError = () => PipelineError
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
      * Exclusively run Redis commands as part of a pipeline (autoflush: disabled).
      *
      * Once all the commands have been executed, @exec will "flush" them into Redis,
      * and finally re-enable autoflush.
      *
      * @return `F[R]` or raises a @PipelineError in case of failure.
      */
    def exec[T <: HList, R <: HList](commands: T)(implicit w: Witness.Aux[T, R]): F[R] =
      Runner[F].exec(ops)(commands)

  }

}
