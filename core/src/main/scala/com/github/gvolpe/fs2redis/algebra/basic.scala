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

package com.github.gvolpe.fs2redis.algebra

import scala.concurrent.duration.FiniteDuration

trait BasicCommands[F[_], K, V] extends Expiration[F, K, V] {
  def get(k: K): F[Option[V]]
  def set(k: K, v: V): F[Unit]
  def setnx(k: K, v: V): F[Unit]
  def del(k: K): F[Unit]
}

trait Expiration[F[_], K, V] {
  def setex(k: K, v: V, seconds: FiniteDuration): F[Unit]
  def expire(k: K, seconds: FiniteDuration): F[Unit]
}
