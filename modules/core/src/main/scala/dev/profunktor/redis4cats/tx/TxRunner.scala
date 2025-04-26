/*
 * Copyright 2018-2025 ProfunKtor
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

import dev.profunktor.redis4cats.effect.TxExecutor

private[redis4cats] trait TxRunner[F[_]] {
  def run[A](
      acquire: F[Unit],
      release: F[Unit],
      onError: F[Unit]
  )(
      fs: TxStore[F, String, A] => List[F[Unit]]
  ): F[Map[String, A]]
  def liftK[G[_]: Async]: TxRunner[G]
}

private[redis4cats] object TxRunner {
  private[redis4cats] def make[F[_]: Async](t: TxExecutor[F]): TxRunner[F] =
    new TxRunner[F] {
      def run[A](
          acquire: F[Unit],
          release: F[Unit],
          onError: F[Unit]
      )(
          fs: TxStore[F, String, A] => List[F[Unit]]
      ): F[Map[String, A]] =
        TxStore.make[F, String, A].flatMap { store =>
          (Deferred[F, Unit], Ref.of[F, List[Fiber[F, Throwable, Unit]]](List.empty)).tupled.flatMap {
            case (gate, fbs) =>
              t.eval(acquire)
                .bracketCase { _ =>
                  fs(store)
                    .traverse_(f => t.start(f).flatMap(fb => fbs.update(_ :+ fb)))
                    .guarantee(gate.complete(()).void)
                } {
                  case (_, Outcome.Succeeded(_)) =>
                    gate.get *> t.eval(release).guarantee(fbs.get.flatMap(_.traverse_(_.join)))
                  case (_, _) =>
                    t.eval(onError).guarantee(fbs.get.flatMap(_.traverse_(_.cancel)))
                }
          } *> store.get
        }

      def liftK[G[_]: Async]: TxRunner[G] = make[G](t.liftK[G])
    }
}
