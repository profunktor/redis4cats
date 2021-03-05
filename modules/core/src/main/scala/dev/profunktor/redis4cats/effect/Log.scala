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

import cats.Applicative
import cats.effect.Sync

/**
  * Typeclass used for internal logging such as acquiring and releasing connections.
  *
  * It is recommended to use `log4cats` for production usage but if you do not want
  * the extra dependency, you can opt to use either of the simple instances provided.
  *
  * If you don't need logging at all, you can use [[Log.NoOp]]
  *
  * {{{
  * import dev.profunktor.redis4cats.effect.Log.NoOp._
  * }}}
  *
  * If you need simple logging to STDOUT for quick debugging, you can use [[Log.Stdout]]
  *
  * {{{
  * import dev.profunktor.redis4cats.effect.Log.Stdout._
  * }}}
  * */
trait Log[F[_]] {
  def debug(msg: => String): F[Unit]
  def error(msg: => String): F[Unit]
  def info(msg: => String): F[Unit]
}

object Log {
  def apply[F[_]](implicit ev: Log[F]): Log[F] = ev

  object NoOp {
    implicit def instance[F[_]: Applicative]: Log[F] =
      new Log[F] {
        def debug(msg: => String): F[Unit] = F.unit
        def error(msg: => String): F[Unit] = F.unit
        def info(msg: => String): F[Unit]  = F.unit
      }
  }

  object Stdout {
    implicit def instance[F[_]: Sync]: Log[F] =
      new Log[F] {
        def debug(msg: => String): F[Unit] =
          F.delay(Console.out.println(msg))
        def error(msg: => String): F[Unit] =
          F.delay(Console.err.println(msg))
        def info(msg: => String): F[Unit] =
          F.delay(Console.out.println(msg))
      }
  }

}
