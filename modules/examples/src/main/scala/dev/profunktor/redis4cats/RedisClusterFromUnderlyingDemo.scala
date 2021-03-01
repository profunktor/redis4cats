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

import java.time.Duration

import cats.effect.{ Blocker, IO, Resource }
import dev.profunktor.redis4cats.connection.{ RedisClusterClient, RedisURI }
import dev.profunktor.redis4cats.effect.JRFuture
import dev.profunktor.redis4cats.effect.Log.NoOp._
import io.lettuce.core.TimeoutOptions
import io.lettuce.core.cluster.{ ClusterClientOptions, RedisClusterClient => JRedisClusterClient }

import scala.concurrent.ExecutionContext
import dev.profunktor.redis4cats.effect.RedisBlocker

object RedisClusterFromUnderlyingDemo extends LoggerIOApp {

  import Demo._

  val program: IO[Unit] = {
    val usernameKey = "test"

    val commandsApi =
      for {
        uri <- Resource.liftF(RedisURI.make[IO](redisClusterURI))
        blocker = RedisBlocker(Blocker.liftExecutionContext(ExecutionContext.global))
        underlying <- Resource.make(IO {
                       val timeoutOptions =
                         TimeoutOptions
                           .builder()
                           .fixedTimeout(Duration.ofMillis(500L))
                           .build()
                       val clusterOptions =
                         ClusterClientOptions
                           .builder()
                           .pingBeforeActivateConnection(true)
                           .autoReconnect(true)
                           .cancelCommandsOnReconnectFailure(true)
                           .validateClusterNodeMembership(true)
                           .timeoutOptions(timeoutOptions)
                           .build()

                       val client = JRedisClusterClient.create(uri.underlying)
                       client.setOptions(clusterOptions)
                       client
                     })(client => JRFuture.fromCompletableFuture(IO(client.shutdownAsync()))(blocker).void)
        client = RedisClusterClient.fromUnderlying(underlying)
        redis <- Redis[IO].fromClusterClient(client, stringCodec)()
      } yield redis

    commandsApi
      .use { cmd =>
        for {
          maybeValue <- cmd.get(usernameKey)
          _ <- putStrLn(maybeValue)
        } yield ()
      }
  }

}
