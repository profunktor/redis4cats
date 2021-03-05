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

import cats.data.EitherT
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.hlist.HNil
import dev.profunktor.redis4cats.effect.Log.NoOp._
import dev.profunktor.redis4cats.transactions._

class OptimisticLockSuite extends IOSuite {

  private val redisURI    = "redis://localhost:6379"
  private val mkRedis     = RedisClient[IO].from(redisURI)
  private val Parallelism = 10

  private val testKey      = "tx-lock-key"
  private val InitialValue = "a"
  private val UpdatedValue = "b"

  test("Optimistic lock allows single update") {
    mkRedis
      .use(client => setupTestData(client) >> concurrentUpdates(client))
      .map { results =>
        val (left, right) = results.separate
        assertEquals(left.size, Parallelism - 1)
        assertEquals(right.size, 1)
      }
  }

  private def setupTestData(client: RedisClient): IO[Unit] =
    commands(client).use(cmds => cmds.flushAll >> cmds.set(testKey, InitialValue))

  private def concurrentUpdates(client: RedisClient): IO[List[Either[String, Unit]]] =
    (Deferred[IO, Unit], Ref.of[IO, Int](0)).parTupled.flatMap {
      case (promise, counter) =>
        // A promise to make sure all the connections call WATCH before running the transaction
        def attemptComplete = counter.get.flatMap { count =>
          promise.complete(()).attempt.void.whenA(count === Parallelism)
        }
        List.range(0, Parallelism).as(exclusiveUpdate(client, promise, counter, attemptComplete)).parSequence
    }

  private def exclusiveUpdate(
      client: RedisClient,
      promise: Deferred[IO, Unit],
      counter: Ref[IO, Int],
      attemptComplete: IO[Unit]
  ): IO[Either[String, Unit]] =
    commands(client).use { cmd =>
      EitherT
        .right[String](cmd.watch(testKey))
        .semiflatMap(_ => counter.update(_ + 1) >> attemptComplete >> promise.get)
        .flatMapF(_ =>
          RedisTransaction(cmd)
            .exec(cmd.set(testKey, UpdatedValue) :: HNil)
            .void
            .as(Either.right[String, Unit](()))
            .recover {
              case TransactionAborted | TransactionDiscarded => Left("Discarded")
            }
            .uncancelable
        )
        .value
    }

  private def commands(client: RedisClient): Resource[IO, RedisCommands[IO, String, String]] =
    Redis[IO].fromClient(client, RedisCodec.Ascii)

}
