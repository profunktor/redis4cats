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

package dev.profunktor.redis4cats.syntax

import dev.profunktor.redis4cats.connection.RedisURI
import org.typelevel.literally.Literally
import scala.language.`3.0`

object literals {
  extension (inline ctx: StringContext){
    inline def redis(inline args: Any*):RedisURI = ${RedisLiteral('ctx, 'args)}
  }

  object RedisLiteral extends Literally[RedisURI]{
    def validate(s: String)(using Quotes) = 
        RedisURI.fromString(s) match {
            case Left(e)  => Left(e.getMessage)
            case Right(_) => Right('{RedisURI.unsafeFromString(${ Expr(s) })})
      }
  }
}