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

trait JsonPath
trait JsonValue[K,V]

trait Json[F[_],K,V] extends JsonArray[F,K,V] with JsonGet[F,K,V] with JsonSet[F,K,V] with JsonNumber[F,K,V] with JsonString[F,K,V] with JsonBoolean[F,K,V] {
  def clear(key: K,path: JsonPath): F[Long]
  def del(key: K,path: JsonPath): F[Long]
  def jsonType(key: K,path: JsonPath): F[V]
  def jsonToggle(key: K,path: JsonPath): F[Long]
  def get(key: K,path: JsonPath): F[JsonValue[K,V]]

}
trait JsonGet[F[_],K,V] {
  def get(key: K,path: JsonPath): F[JsonValue[K,V]]
  def objKeys(key: K,path: JsonPath): F[List[K]]
  def objLen(key: K,path: JsonPath): F[Long]
  def mget(key: K,paths: JsonPath*): F[List[JsonValue[K,V]]]
}
trait JsonSet[F[_],K,V] {
  def mset(key: K,values: (JsonPath,JsonValue[K,V])*): F[Unit]
  def set(key: K,path: JsonPath,value: JsonValue[K,V]): F[Unit]
  def setnx(key: K,path: JsonPath,value: JsonValue[K,V]): F[Boolean]
  def setxx(key: K,path: JsonPath,value: JsonValue[K,V]): F[Boolean]
  def jsonMerge(key:K, jsonPath: JsonPath, value: JsonValue[K,V]): F[Boolean];
}
trait JsonNumber[F[_],K,V] {
  def numIncrBy(key: K,path: JsonPath,number: Number): F[List[Number]]
}
trait JsonString[F[_],K,V] {
  def strAppend(key: K,path: JsonPath,value: JsonValue[K,V]): F[Long]
  def strLen(key: K,path: JsonPath): F[Long]
}
trait JsonBoolean[F[_],K,V] {
  def toggle(key: K,path: JsonPath): F[Boolean]
}

trait JsonArray[F[_],K,V] {
  def arrAppend(key: K,path: JsonPath,value: JsonValue[K,V]*): F[Unit]
  def arrIndex(key: K,path: JsonPath,value: JsonValue[K,V],range: JsonRangeArgs): F[List[Long]]
  def arrInsert(key: K,path: JsonPath,index: Int,value: JsonValue[K,V]*): F[List[Long]]
  def arrLen(key: K,path: JsonPath): F[List[Long]]
  def arrPop(key: K,path: JsonPath,index: Int): F[List[JsonValue[K,V]]]
  def arrTrim(key: K,path: JsonPath,range: JsonRangeArgs): F[List[Long]]

}
