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

package dev.profunktor.redis4cats.syntax

import dev.profunktor.redis4cats.connection.RedisURI
import org.typelevel.literally.Literally

object macros {

  object RedisLiteral extends Literally[RedisURI] {

    override def validate(c: Context)(s: String): Either[String, c.Expr[RedisURI]] = {
      import c.universe._
      RedisURI.fromString(s) match {
        case Left(e)  => Left(e.getMessage)
        case Right(_) => Right(c.Expr(q"dev.profunktor.redis4cats.connection.RedisURI.unsafeFromString($s)"))
      }
    }

    def make(c: Context)(args: c.Expr[Any]*): c.Expr[RedisURI] = apply(c)(args: _*)
  }

}
