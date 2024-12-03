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

import io.lettuce.core.json.arguments.JsonRangeArgs
import io.lettuce.core.json.{ JsonPath, JsonType, JsonValue }

trait JsonCommands[F[_], K, V]
    extends JsonArray[F, K, V]
    with JsonGet[F, K, V]
    with JsonSet[F, K, V]
    with JsonNumber[F, K, V]
    with JsonString[F, K, V]
    with JsonBoolean[F, K, V] {
  def clear(key: K, path: JsonPath): F[Long]
  // def debug(key: K, path: JsonPath): F[String]
  def del(key: K, path: JsonPath): F[Long]
//  def forget(key: K, path: JsonPath): F[Long]
  def jsonType(key: K, path: JsonPath): F[List[JsonType]]
}
trait JsonGet[F[_], K, V] {
  def get(key: K, path: JsonPath): F[List[JsonValue]]
  def mget(key: K, paths: JsonPath*): F[List[JsonValue]]
  def objKeys(key: K, path: JsonPath): F[List[K]]
  def objLen(key: K, path: JsonPath): F[Long]
}
trait JsonSet[F[_], K, V] {
  def mset(key: K, values: (JsonPath, JsonValue)*): F[Unit]
  def set(key: K, path: JsonPath, value: JsonValue): F[Unit]
  def setnx(key: K, path: JsonPath, value: JsonValue): F[Boolean]
  def setxx(key: K, path: JsonPath, value: JsonValue): F[Boolean]
  def jsonMerge(key: K, jsonPath: JsonPath, value: JsonValue): F[Boolean];
}
trait JsonNumber[F[_], K, V] {
  def numIncrBy(key: K, path: JsonPath, number: Number): F[List[Number]]
}
trait JsonString[F[_], K, V] {
  def strAppend(key: K, path: JsonPath, value: JsonValue): F[Long]
  def strLen(key: K, path: JsonPath): F[Long]
}
trait JsonBoolean[F[_], K, V] {
  def toggle(key: K, path: JsonPath): F[Boolean]
}

trait JsonArray[F[_], K, V] {
  def arrAppend(key: K, path: JsonPath, value: JsonValue*): F[Unit]
  def arrIndex(key: K, path: JsonPath, value: JsonValue, range: JsonRangeArgs): F[List[Long]]
  def arrInsert(key: K, path: JsonPath, index: Int, value: JsonValue*): F[List[Long]]
  def arrLen(key: K, path: JsonPath): F[List[Long]]
  def arrPop(key: K, path: JsonPath, index: Int): F[List[JsonValue]]
  def arrTrim(key: K, path: JsonPath, range: JsonRangeArgs): F[List[Long]]

}
