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

package dev.profunktor.redis4cats.algebra

trait ConnectionCommands[F[_]] extends Ping[F] with Auth[F]

trait Ping[F[_]] {
  def ping: F[String]
  def select(index: Int): F[Unit]
}

trait Auth[F[_]] {
  def auth(password: CharSequence): F[Boolean]
  def auth(username: String, password: CharSequence): F[Boolean]
}
