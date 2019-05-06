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

package dev.profunktor.fs2redis

import cats.effect.{ ExitCode, IO, IOApp }
import cats.syntax.functor._
import dev.profunktor.fs2redis.effect.Log
import dev.profunktor.fs2redis.log4cats._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

/**
  * Provides an instance of `Log` given an instance of `Logger`.
  *
  * For simplicity and re-usability in all the examples.
  * */
trait LoggerIOApp extends IOApp {

  def program(implicit log: Log[IO]): IO[Unit]

  override def run(args: List[String]): IO[ExitCode] =
    Slf4jLogger
      .create[IO]
      .flatMap { implicit logger: Logger[IO] =>
        program
      }
      .as(ExitCode.Success)

}
