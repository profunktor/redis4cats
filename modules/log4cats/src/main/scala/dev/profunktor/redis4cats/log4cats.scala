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

import dev.profunktor.redis4cats.effect.Log
import org.typelevel.log4cats.Logger

object log4cats {

  implicit def log4CatsInstance[F[_]: Logger]: Log[F] =
    new Log[F] {
      def debug(msg: => String): F[Unit] = F.debug(msg)
      def error(msg: => String): F[Unit] = F.error(msg)
      def info(msg: => String): F[Unit]  = F.info(msg)
    }

}
