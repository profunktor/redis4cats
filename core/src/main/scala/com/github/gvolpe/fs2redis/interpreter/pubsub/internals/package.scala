/*
 * Copyright 2018 Fs2 Redis
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

package com.github.gvolpe.fs2redis.interpreter.pubsub

import com.github.gvolpe.fs2redis.model.Fs2RedisChannel
import fs2.async.mutable
import fs2.async.mutable.Topic

package object internals {
  private[pubsub] type PubSubState[F[_], K, V] = Map[K, mutable.Topic[F, Option[V]]]
  private[pubsub] type GetOrCreateTopicListener[F[_], K, V] =
    Fs2RedisChannel[K] => PubSubState[F, K, V] => F[Topic[F, Option[V]]]
}
