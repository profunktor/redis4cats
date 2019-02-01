/*
 * Copyright 2018-2019 Gabriel Volpe
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

package com.github.gvolpe.fs2redis.effect

/**
  * Typeclass used for internal logging such as acquiring and releasing connections.
  *
  * You should provide an instance. It is recommended to use `log4cats`.
  * */
trait Log[F[_]] {
  def info(msg: => String): F[Unit]
  def error(msg: => String): F[Unit]
}

object Log {
  def apply[F[_]](implicit ev: Log[F]): Log[F] = ev
}
