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
import cats.effect.concurrent.Deferred
import cats.effect.implicits._
import cats.implicits._
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.hlist._
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object pipeline {

  case object PipelineError extends NoStackTrace

  case class RedisPipeline[F[_]: Concurrent: Log: Timer, K, V](
      cmd: RedisCommands[F, K, V]
  ) extends HListRunner[F] {

    def exec[T <: HList, R <: HList](commands: T)(implicit w: Witness.Aux[T, R]): F[R] =
      Deferred[F, Either[Throwable, w.R]].flatMap { promise =>
        F.info("Pipeline started") >>
          Resource
            .makeCase(cmd.disableAutoFlush >> runner(commands, HNil)) {
              case ((fibs: HList), ExitCase.Completed) =>
                for {
                  _ <- F.info("Pipeline completed")
                  _ <- cmd.flushCommands
                  tr <- joinOrCancel(fibs, HNil)(true)
                  // Casting here is fine since we have a `Witness` that proves this true
                  _ <- promise.complete(tr.asInstanceOf[w.R].asRight)
                } yield ()
              case ((fibs: HList), ExitCase.Error(e)) =>
                F.error(s"Pipeline failed: ${e.getMessage}") >> cancelFibers(fibs, PipelineError, promise)
              case ((fibs: HList), ExitCase.Canceled) =>
                F.error("Pipeline canceled") >> cancelFibers(fibs, PipelineError, promise)
              case _ =>
                F.error("Kernel panic: the impossible happened!")
            }
            .use(_ => F.unit)
            .guarantee(cmd.enableAutoFlush) >> promise.get.rethrow.timeout(3.seconds)
      }

  }

}
