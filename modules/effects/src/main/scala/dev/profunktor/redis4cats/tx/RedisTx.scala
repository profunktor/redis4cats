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

package dev.profunktor.redis4cats.tx

import cats.effect.kernel._
import cats.effect.kernel.syntax.all._
import cats.syntax.all._

import dev.profunktor.redis4cats.RedisCommands
import dev.profunktor.redis4cats.effect.TxExecutor

trait RedisTx[F[_]] {
  def run(fs: List[F[Unit]]): F[Unit]
}

object RedisTx {

  /**
    * You should ideally create a single instance for your application, as it
    * creates an internal [[ExecutionContext]] used exclusively for transactions,
    * which can be an expensive task.
    */
  def make[F[_]: Async](
      redis: RedisCommands[F, String, String]
  ): Resource[F, RedisTx[F]] =
    TxExecutor.make[F].map { t =>
      new RedisTx[F] {
        def run(fs: List[F[Unit]]): F[Unit] =
          (Deferred[F, Unit], Ref.of[F, List[Fiber[F, Throwable, Unit]]](List.empty)).tupled.flatMap {
            case (gate, fbs) =>
              t.eval(redis.multi)
                .bracketCase { _ =>
                  fs.traverse_(f => t.eval(f).start.flatMap(fb => fbs.update(_ :+ fb)))
                    .guarantee(gate.complete(()).void)
                } {
                  case (_, Outcome.Succeeded(_)) =>
                    gate.get *> t.eval(redis.exec) *> fbs.get.flatMap(_.traverse_(_.join))
                  case (_, _) =>
                    t.eval(redis.discard) *> fbs.get.flatMap(_.traverse_(_.cancel))
                }
          }
      }
    }
}
