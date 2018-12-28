/*
 * Copyright 2018-2019 Fs2 Redis
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

package com.github.gvolpe.fs2redis

import cats.effect.{ ExitCode, IO, IOApp, Resource }
import cats.syntax.all._
import com.github.gvolpe.fs2redis.algebra.SortedSetCommands
import com.github.gvolpe.fs2redis.connection.Fs2RedisClient
import com.github.gvolpe.fs2redis.effects.{ Score, ScoreWithValue, ZRange }
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis

object Fs2RedisSortedSetsDemo extends IOApp {

  import Demo._

  override def run(args: List[String]): IO[ExitCode] = {
    val testKey = "zztop"

    val commandsApi: Resource[IO, SortedSetCommands[IO, String, Long]] =
      for {
        client <- Fs2RedisClient[IO](redisURI)
        redis <- Fs2Redis[IO, String, Long](client, longCodec, redisURI)
      } yield redis

    commandsApi
      .use { cmd =>
        for {
          _ <- cmd.zAdd(testKey, args = None, ScoreWithValue(Score(1), 1), ScoreWithValue(Score(3), 2))
          x <- cmd.zRevRangeByScore(testKey, ZRange(0, 2), limit = None)
          _ <- putStrLn(s"Score: $x")
          y <- cmd.zCard(testKey)
          _ <- putStrLn(s"Size: $y")
          z <- cmd.zCount(testKey, ZRange(0, 1))
          _ <- putStrLn(s"Count: $z")
        } yield ()
      }
      .as(ExitCode.Success)
  }

}
