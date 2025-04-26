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

package dev.profunktor.redis4cats.pubsub.internals

import cats.Applicative
import fs2.concurrent.Topic

/** Stores an ongoing subscription.
  *
  * @param topic
  *   single-publisher, multiple-subscribers. The same topic is reused if `subscribe` is invoked more than once. The
  *   subscribers' streams are terminated when `None` is published.
  * @param subscribers
  *   subscriber count, when `subscribers` reaches 0 `cleanup` is called and `None` is published to the topic.
  */
final private[redis4cats] case class Redis4CatsSubscription[F[_], V](
    topic: Topic[F, Option[V]],
    subscribers: Long,
    cleanup: F[Unit]
) {
  assert(subscribers > 0, s"subscribers must be > 0, was $subscribers")

  def addSubscriber: Redis4CatsSubscription[F, V]    = copy(subscribers = subscribers + 1)
  def removeSubscriber: Redis4CatsSubscription[F, V] = copy(subscribers = subscribers - 1)
  def isLastSubscriber: Boolean                      = subscribers == 1

  def stream(onTermination: F[Unit])(
      implicit F: Applicative[F]
  ): fs2.Stream[F, V] =
    topic.subscribe(500).unNoneTerminate.onFinalize(onTermination)
}
