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

package dev.profunktor.redis4cats

import cats.effect.{ IO, Resource }
import dev.profunktor.redis4cats.algebra.ScriptCommands
import dev.profunktor.redis4cats.effect.Log.NoOp._
import dev.profunktor.redis4cats.effects.ScriptOutputType

object RedisScriptsDemo extends LoggerIOApp {

  import Demo._

  val program: IO[Unit] = {
    val commandsApi: Resource[IO, ScriptCommands[IO, String, String]] =
      Redis[IO].utf8(redisURI)

    commandsApi
      .use { cmd =>
        for {
          greeting <- cmd.eval("return 'Hello World'", ScriptOutputType.Value)
          _ <- putStrLn(s"Greetings from Lua: $greeting")
          fortyTwo <- cmd.eval("return 42", ScriptOutputType.Integer)
          _ <- putStrLn(s"Answer to the Ultimate Question of Life, the Universe, and Everything: $fortyTwo")
          list <- cmd.eval(
                   "return {'Let', 'us', ARGV[1], ARGV[2]}",
                   ScriptOutputType.Multi,
                   Nil,
                   List("have", "fun")
                 )
          _ <- putStrLn(s"We can even return lists: $list")
          randomScript = "math.randomseed(tonumber(ARGV[1])); return math.random() * 1000"
          shaRandom <- cmd.scriptLoad(randomScript)
          exists <- cmd.scriptExists(shaRandom)
          _ <- putStrLn(s"Script is cached on Redis: $exists")
          // seeding the RNG with 7
          random <- cmd.evalSha(shaRandom, ScriptOutputType.Integer, Nil, List("7"))
          _ <- putStrLn(s"Execution of cached script returns a pseudo-random number: $random")
          scriptDigest <- cmd.digest(randomScript)
          exists3 <- cmd.scriptExists(scriptDigest)
          _ <- putStrLn(s"Locally computed script digest is the same sha as Redis: $exists3")
          () <- cmd.scriptFlush
          _ <- putStrLn("Flushed all cached scripts!")
          exists2 <- cmd.scriptExists(shaRandom)
          _ <- putStrLn(s"Script is still cached on Redis: $exists2")
        } yield ()
      }
  }

}
