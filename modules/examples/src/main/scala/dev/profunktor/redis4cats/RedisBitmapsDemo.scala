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
import cats.syntax.all._
import dev.profunktor.redis4cats.algebra.BitCommandOperation.{ IncrUnsignedBy, SetUnsigned }
import dev.profunktor.redis4cats.algebra.BitCommands
import dev.profunktor.redis4cats.effect.Log.NoOp._

object RedisBitmapsDemo extends LoggerIOApp {

  import Demo._

  val program: IO[Unit] = {
    val testKey = "bitsets"

    val generalApi: Resource[IO, RedisCommands[IO, String, String]] = Redis[IO].utf8(redisURI)
    val bitmapsApi: Resource[IO, BitCommands[IO, String, String]]   = Redis[IO].utf8(redisURI)

    (bitmapsApi, generalApi).tupled.use { case (bits, strings) =>
      for {
        _ <- strings.del(testKey)
        a <- bits.setBit(testKey, 7, 1)
        b <- bits.setBit(testKey, 7, 0)
        _ <- IO.println(s"Before $a after $b")
        cSet <- bits.setBit(testKey, 6, 1)
        _ <- IO.println(s"Setting offset 6 to $cSet")
        c <- bits.getBit(testKey, 6)
        _ <- IO.println(s"Bit at offset 6 is $c")
        batchSet <- for {
                      s1 <- bits.setBit("bitmapsarestrings", 2, 1)
                      s2 <- bits.setBit("bitmapsarestrings", 3, 1)
                      s3 <- bits.setBit("bitmapsarestrings", 5, 1)
                      s4 <- bits.setBit("bitmapsarestrings", 10, 1)
                      s5 <- bits.setBit("bitmapsarestrings", 11, 1)
                      s6 <- bits.setBit("bitmapsarestrings", 14, 1)
                    } yield s1 + s2 + s3 + s4 + s5 + s6
        _ <- IO.println(s"Set multiple $batchSet")
        truth <- strings.get("bitmapsarestrings")
        _ <- IO.println(s"The answer to everything is $truth")
        bf <- bits.bitField(
                "inmap",
                SetUnsigned(2, 1),
                SetUnsigned(3, 1),
                SetUnsigned(5, 1),
                SetUnsigned(10, 1),
                SetUnsigned(11, 1),
                SetUnsigned(14, 1),
                IncrUnsignedBy(14, 1)
              )
        _ <- IO.println(s"Via bitfield $bf")
      } yield ()
    }
  }
}
