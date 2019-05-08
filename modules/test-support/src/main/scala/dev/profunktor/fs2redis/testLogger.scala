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

import java.time.Instant
import java.util.concurrent.TimeUnit

import cats.Functor
import cats.effect.{ Clock, Sync }
import cats.syntax.all._
import dev.profunktor.redis4cats.effect.Log

object testLogger {

  private def putStrLn[F[_]: Sync, A](a: A): F[Unit] = Sync[F].delay(println(a))

  private def timestamp[F[_]: Clock: Functor]: F[String] =
    Clock[F].realTime(TimeUnit.MILLISECONDS).map(Instant.ofEpochMilli(_).toString)

  private def log[F[_]: Clock: Sync, A](level: String)(a: A): F[Unit] =
    timestamp[F].flatMap { t =>
      putStrLn(s"[${level.toUpperCase}] - [$t] - $a")
    }

  implicit def instance[F[_]: Clock: Sync]: Log[F] =
    new Log[F] {
      def info(msg: => String): F[Unit]  = log("info")(msg)
      def error(msg: => String): F[Unit] = log("error")(msg)
    }

}
