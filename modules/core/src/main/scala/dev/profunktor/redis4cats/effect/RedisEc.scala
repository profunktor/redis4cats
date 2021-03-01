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

package dev.profunktor.redis4cats.effect

import cats.effect._
import java.util.concurrent.Executors

trait RedisEc {
  def delay[F[_]: Sync: ContextShift, A](thunk: => A): F[A]
  def eval[F[_]: ContextShift, A](fa: F[A]): F[A]
}

object RedisEc {
  private def apply(blocker: Blocker): RedisEc =
    new RedisEc {
      override def delay[F[_]: Sync: ContextShift, A](thunk: => A): F[A] = blocker.delay(thunk)
      override def eval[F[_]: ContextShift, A](fa: F[A]): F[A]           = blocker.blockOn(fa)
    }

  def apply[F[_]: Sync]: Resource[F, RedisEc] =
    Blocker.fromExecutorService(F.delay(Executors.newFixedThreadPool(1))).map(RedisEc.apply)
}
