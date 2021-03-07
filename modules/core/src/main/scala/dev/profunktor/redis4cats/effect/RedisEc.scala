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

package dev.profunktor.redis4cats.effect

import cats.effect._
import java.util.concurrent.Executors

private[redis4cats] trait RedisExecutor[F[_]] {
  def delay[A](thunk: => A): F[A]
  def eval[A](fa: F[A]): F[A]
  def liftK[G[_]: Concurrent: ContextShift]: RedisExecutor[G]
}

private[redis4cats] object RedisExecutor {
  def apply[F[_]](implicit redisExecutor: RedisExecutor[F]): RedisExecutor[F] = redisExecutor

  def make[F[_]: ContextShift: Sync]: Resource[F, RedisExecutor[F]] =
    Blocker.fromExecutorService(F.delay(Executors.newFixedThreadPool(1))).map(apply[F])

  private def apply[F[_]: ContextShift: Sync](ec: Blocker): RedisExecutor[F] =
    new RedisExecutor[F] {
      def delay[A](thunk: => A): F[A]                             = ec.delay(thunk)
      def eval[A](fa: F[A]): F[A]                                 = ec.blockOn(fa)
      def liftK[G[_]: Concurrent: ContextShift]: RedisExecutor[G] = apply[G](ec)
    }
}
