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

import cats.MonadThrow
import cats.effect.kernel.Clock
import cats.kernel.Monoid
import cats.syntax.all._
import io.lettuce.core.RedisCommandTimeoutException

import scala.concurrent.duration.FiniteDuration

/**
  * Configures restarting operations in case they time out.
  *
  * This is useful because Lettuce (the underlying Java client) does time out some operations if they do not send
  * any data, like reading from a stream.
  */
trait RestartOnTimeout {

  /**
    * @param elapsed amount of time elapsed from the start of operation
    * @return `true` if the operation should be restarted
    */
  def apply(elapsed: FiniteDuration): Boolean

  /** Wraps the given operation into a restart loop. */
  def wrap[F[_], A](fa: F[A])(implicit clock: Clock[F], monadThrow: MonadThrow[F], monoid: Monoid[F[A]]): F[A] = {
    val currentTime = clock.monotonic

    def onTimeout(startedAt: FiniteDuration): F[A] =
      for {
        now <- currentTime
        elapsed = now - startedAt
        restart = apply(elapsed)
        a <- if (restart) doOp else monoid.empty
      } yield a

    def doOp: F[A] =
      for {
        startedAt <- currentTime
        a <- fa.recoverWith { case _: RedisCommandTimeoutException => onTimeout(startedAt) }
      } yield a

    doOp
  }
}
object RestartOnTimeout {

  /** Always restart. */
  def always: RestartOnTimeout = _ => true

  /** Never restart. */
  def never: RestartOnTimeout = _ => false

  /** Restart if the elapsed time is less than the given duration. */
  def ifBefore(duration: FiniteDuration): RestartOnTimeout = elapsed => elapsed < duration

  /** Restart if the elapsed time is greater than the given duration. */
  def ifAfter(duration: FiniteDuration): RestartOnTimeout = elapsed => elapsed > duration
}
