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

package dev.profunktor.redis4cats.extensions

import cats.effect.kernel.{ Resource, Sync }
import cats.syntax.all._
import cats.{ ApplicativeThrow, Functor }
import dev.profunktor.redis4cats.algebra.Scripting
import dev.profunktor.redis4cats.effects.ScriptOutputType
import io.lettuce.core.RedisNoScriptException

object luaScripting {

  final case class LuaScript(contents: String, sha: String)

  object LuaScript {

    def make[F[_]: Functor](redis: Scripting[F, _, _])(contents: String): F[LuaScript] =
      redis.digest(contents).map(sha => LuaScript(contents, sha))

    /** Helper to load a lua script from resources/lua/{resourceName}. The path to the lua scripts can be configured.
      * @param redis
      *   redis commands
      * @param resourceName
      *   filename of the lua script
      * @param pathToScripts
      *   path to the lua scripts
      * @return
      *   [[LuaScript]]
      */
    def loadFromResources[F[_]: Sync](redis: Scripting[F, _, _])(
        resourceName: String,
        pathToScripts: String = "lua"
    ): F[LuaScript] =
      Resource
        .fromAutoCloseable(
          Sync[F].blocking(
            scala.io.Source.fromResource(resource = s"$pathToScripts/$resourceName")
          )
        )
        .evalMap(fileSrc => Sync[F].blocking(fileSrc.mkString))
        .use(make(redis))

  }

  implicit class LuaScriptingExtensions[F[_]: ApplicativeThrow, K, V](redis: Scripting[F, K, V]) {

    /** Evaluate the cached lua script via it's sha. If the script is not cached, fallback to evaluating the script
      * directly.
      * @param luaScript
      *   the lua script with its content and sha
      * @param output
      *   output of script
      * @param keys
      *   keys to script
      * @param values
      *   values to script
      * @return
      *   ScriptOutputType
      */
    def evalLua(
        luaScript: LuaScript,
        output: ScriptOutputType[V],
        keys: List[K],
        values: List[V]
    ): F[output.R] =
      redis
        .evalSha(
          luaScript.sha,
          output,
          keys,
          values
        )
        .recoverWith { case _: RedisNoScriptException =>
          redis.eval(luaScript.contents, output, keys, values)
        }

  }
}
