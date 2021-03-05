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

import cats.effect._
import cats.syntax.all._
import dev.profunktor.redis4cats.effect.Log.NoOp._
import dev.profunktor.redis4cats.hlist._
import dev.profunktor.redis4cats.pipeline._
import java.util.concurrent.TimeoutException

object RedisPipelineDemo extends LoggerIOApp {

  import Demo._

  val program: IO[Unit] = {
    val key1 = "testp1"
    val key2 = "testp2"

    val showResult: String => Option[String] => IO[Unit] = key =>
      _.fold(putStrLn(s"Not found key: $key"))(s => putStrLn(s"$key: $s"))

    val commandsApi: Resource[IO, RedisCommands[IO, String, String]] =
      Redis[IO].utf8(redisURI)

    commandsApi
      .use { cmd =>
        val getters =
          cmd.get(key1).flatTap(showResult(key1)) *>
              cmd.get(key2).flatTap(showResult(key2))

        val operations =
          cmd.set(key1, "noop") :: cmd.set(key2, "windows") :: cmd.get(key1) ::
              cmd.set(key1, "nix") :: cmd.set(key2, "linux") :: cmd.get(key1) :: HNil

        val prog =
          RedisPipeline(cmd)
            .filterExec(operations)
            .flatMap {
              case res1 ~: res2 ~: HNil =>
                putStrLn(s"res1: $res1, res2: $res2")
            }
            .onError {
              case PipelineError =>
                putStrLn("[Error] - Pipeline failed")
              case _: TimeoutException =>
                putStrLn("[Error] - Timeout")
            }

        getters >> prog >> getters >> putStrLn("keep doing stuff...")
      }
  }

}
