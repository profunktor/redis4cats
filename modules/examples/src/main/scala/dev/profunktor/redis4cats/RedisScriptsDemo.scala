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

    commandsApi.use { redis =>
      for {
        greeting <- redis.eval("return 'Hello World'", ScriptOutputType.Value)
        _ <- IO.println(s"Greetings from Lua: $greeting")
        fortyTwo <- redis.eval("return 42", ScriptOutputType.Integer)
        _ <- IO.println(s"Answer to the Ultimate Question of Life, the Universe, and Everything: $fortyTwo")
        list <- redis.eval(
                  "return {'Let', 'us', ARGV[1], ARGV[2]}",
                  ScriptOutputType.Multi,
                  Nil,
                  List("have", "fun")
                )
        _ <- IO.println(s"We can even return lists: $list")
        randomScript = "math.randomseed(tonumber(ARGV[1])); return math.random() * 1000"
        shaRandom <- redis.scriptLoad(randomScript)
        l <- redis.scriptExists(shaRandom)
        List(exists) = l
        _ <- IO.println(s"Script is cached on Redis: $exists")
        // seeding the RNG with 7
        random <- redis.evalSha(shaRandom, ScriptOutputType.Integer, Nil, List("7"))
        _ <- IO.println(s"Execution of cached script returns a pseudo-random number: $random")
        scriptDigest <- redis.digest(randomScript)
        l <- redis.scriptExists(scriptDigest)
        List(exists3) = l
        _ <- IO.println(s"Locally computed script digest is the same sha as Redis: $exists3")
        _ <- redis.scriptFlush
        _ <- IO.println("Flushed all cached scripts!")
        l <- redis.scriptExists(shaRandom)
        List(exists2) = l
        _ <- IO.println(s"Script is still cached on Redis: $exists2")
      } yield ()
    }
  }

}
