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

package dev.profunktor.redis4cats.algebra

import dev.profunktor.redis4cats.algebra.json.JsonGetArgs
import io.lettuce.core.json.arguments.{ JsonGetArgs => LJsonGetArgs, JsonRangeArgs, JsonSetArgs }
import io.lettuce.core.json.{ JsonPath, JsonType, JsonValue }

trait JsonCommands[F[_], K, V]
    extends JsonArray[F, K, V]
    with JsonGet[F, K, V]
    with JsonSet[F, K, V]
    with JsonNumber[F, K, V]
    with JsonString[F, K, V]
    with JsonBoolean[F, K, V] {

  /** Clear container values (arrays/objects) and set numeric values to 0
    * @return
    *   Long the number of values removed plus all the matching JSON numerical values that are zeroed.
    */
  def jClear(key: K, path: JsonPath): F[Long]
  def jClear(key: K): F[Long]

  /** Deletes a value inside the JSON document at a given JsonPath
    * @return
    *   Long the number of values removed (0 or more).
    */
  def jDel(key: K, path: JsonPath): F[Long]
  def jDel(key: K): F[Long]

  def jsonType(key: K, path: JsonPath): F[List[JsonType]]
  def jsonType(key: K): F[List[JsonType]]
}
trait JsonGet[F[_], K, V] {
  def jGet(key: K, path: JsonPath, paths: JsonPath*): F[List[JsonValue]]
  def jGet(key: K, arg: JsonGetArgs, path: JsonPath, paths: JsonPath*): F[List[JsonValue]]
  def jMget(path: JsonPath, key: K, keys: K*): F[List[JsonValue]]
  def jObjKeys(key: K, path: JsonPath): F[List[V]]
  def jObjLen(key: K, path: JsonPath): F[Long]
}
trait JsonSet[F[_], K, V] {
  def jMset(key: K, values: (JsonPath, JsonValue)*): F[Boolean]
  def jSet(key: K, path: JsonPath, value: JsonValue): F[Boolean]
  def jSet(key: K, path: JsonPath, value: JsonValue, args: JsonSetArgs): F[Boolean]
  def jSetStr(key: K, path: JsonPath, jsonString: String): F[Boolean]
  def jSetStr(key: K, path: JsonPath, jsonString: String, args: JsonSetArgs): F[Boolean]
  def jSetnx(key: K, path: JsonPath, value: JsonValue): F[Boolean]
  def jSetxx(key: K, path: JsonPath, value: JsonValue): F[Boolean]
  def jsonMerge(key: K, jsonPath: JsonPath, value: JsonValue): F[String]
  def jsonMergeStr(key: K, jsonPath: JsonPath, jsonString: String): F[String]
}
trait JsonNumber[F[_], K, V] {
  def numIncrBy(key: K, path: JsonPath, number: Number): F[List[Number]]
}
trait JsonString[F[_], K, V] {
  def strAppend(key: K, path: JsonPath, value: JsonValue): F[List[Long]]
  def strAppendStr(key: K, path: JsonPath, jsonString: String): F[List[Long]]
  def strAppend(key: K, value: JsonValue): F[List[Long]]
  def strAppendStr(key: K, jsonString: String): F[List[Long]]
  def jsonStrLen(key: K, path: JsonPath): F[List[Long]]
  def jsonStrLen(key: K): F[List[Long]]
}
trait JsonBoolean[F[_], K, V] {
  def toggle(key: K, path: JsonPath): F[List[Long]]
}

trait JsonArray[F[_], K, V] {
  // JsonValue variants
  def arrAppend(key: K, path: JsonPath, value: JsonValue*): F[List[Long]]
  def arrAppend(key: K, value: JsonValue*): F[List[Long]]
  // String variants (for convenience)
  def arrAppendStr(key: K, path: JsonPath, jsonStrings: String*): F[List[Long]]
  def arrAppendStr(key: K, jsonStrings: String*): F[List[Long]]

  def arrIndex(key: K, path: JsonPath, value: JsonValue, range: JsonRangeArgs): F[List[Long]]
  def arrIndex(key: K, path: JsonPath, value: JsonValue): F[List[Long]]
  def arrIndexStr(key: K, path: JsonPath, jsonString: String): F[List[Long]]
  def arrIndexStr(key: K, path: JsonPath, jsonString: String, range: JsonRangeArgs): F[List[Long]]

  // JsonValue variants
  def arrInsert(key: K, path: JsonPath, index: Int, value: JsonValue*): F[List[Long]]
  // String variants (for convenience)
  def arrInsertStr(key: K, path: JsonPath, index: Int, jsonStrings: String*): F[List[Long]]

  def arrLen(key: K, path: JsonPath): F[List[Long]]
  def arrLen(key: K): F[List[Long]]

  def arrPop(key: K, path: JsonPath, index: Int): F[List[JsonValue]]
  def arrPop(key: K, path: JsonPath): F[List[JsonValue]]
  def arrPop(key: K): F[List[JsonValue]]

  def arrTrim(key: K, path: JsonPath, range: JsonRangeArgs): F[List[Long]]

}

object json {
  final case class JsonGetArgs(
      indent: Option[String],
      newline: Option[String],
      space: Option[String]
  ) {
    def underlying: LJsonGetArgs = {
      val args = new LJsonGetArgs()
      indent.foreach(args.indent)
      newline.foreach(args.newline)
      space.foreach(args.space)
      args
    }
  }

  object JsonGetArgs
}
