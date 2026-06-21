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

import dev.profunktor.redis4cats.effects.{ AclError, AclSelector, AclUser }
import munit.FunSuite

/** Offline tests for the pure ACL reply decoder — no Redis required. */
class AclDecoderSuite extends FunSuite {

  private def jlist(xs: Object*): java.util.List[Object] = {
    val l = new java.util.ArrayList[Object]()
    xs.foreach(x => { val _ = l.add(x) })
    l
  }

  private def jmap(pairs: (String, Object)*): java.util.Map[String, Object] = {
    val m = new java.util.LinkedHashMap[String, Object]()
    pairs.foreach { case (k, v) => val _ = m.put(k, v) }
    m
  }

  test("decodeUser parses a populated user") {
    val reply =
      jlist(
        "flags", jlist("on", "nopass"),
        "passwords", jlist(),
        "commands", "+@all",
        "keys", "~*",
        "channels", "&*",
        "selectors", jlist()
      )
    val expected: Either[AclError, Option[AclUser]] =
      Right(Some(AclUser(List("on", "nopass"), Nil, "+@all", "~*", "&*", Nil)))
    assertEquals(AclDecoder.decodeUser(reply), expected)
  }

  test("decodeUser treats Lettuce's nil sentinels (null, [], [null]) as a missing user") {
    val expected: Either[AclError, Option[AclUser]] = Right(None)
    assertEquals(AclDecoder.decodeUser(null), expected)
    assertEquals(AclDecoder.decodeUser(jlist()), expected)
    assertEquals(AclDecoder.decodeUser(jlist(null)), expected)
  }

  test("decodeUser fails (rather than reporting absence) when a present reply has no flags field") {
    val reply = jlist("commands", "+@all", "keys", "~*")
    assert(AclDecoder.decodeUser(reply).left.exists(_.isInstanceOf[AclError.DecodingFailure]))
  }

  test("decodeUser joins an array-valued rule field (older-server shape)") {
    val reply = jlist("flags", jlist("on"), "commands", jlist("-@all", "+get"))
    val expected: Either[AclError, Option[AclUser]] =
      Right(Some(AclUser(List("on"), Nil, "-@all +get", "", "", Nil)))
    assertEquals(AclDecoder.decodeUser(reply), expected)
  }

  test("decodeUser fails on an odd-length (dangling key) reply") {
    val reply = jlist("flags", jlist("on"), "keys")
    assert(AclDecoder.decodeUser(reply).left.exists(_.isInstanceOf[AclError.DecodingFailure]))
  }

  test("decodeUser fails when a field name is not a bulk string") {
    val reply = jlist(jlist("not", "a", "key"), "x")
    assert(AclDecoder.decodeUser(reply).left.exists(_.isInstanceOf[AclError.DecodingFailure]))
  }

  test("decodeUser parses selectors") {
    val reply =
      jlist(
        "flags", jlist("on"),
        "passwords", jlist(),
        "commands", "-@all",
        "keys", "",
        "channels", "",
        "selectors", jlist(jlist("commands", "+get", "keys", "~k1", "channels", ""))
      )
    val expected: Either[AclError, Option[AclUser]] =
      Right(Some(AclUser(List("on"), Nil, "-@all", "", "", List(AclSelector("+get", "~k1", "")))))
    assertEquals(AclDecoder.decodeUser(reply), expected)
  }

  test("decodeUser fails on an unexpected reply element type") {
    val reply = jlist("flags", Integer.valueOf(5))
    assert(AclDecoder.decodeUser(reply).left.exists(_.isInstanceOf[AclError.DecodingFailure]))
  }

  test("decodeUser fails (rather than coercing) on an unexpected null inside a present reply") {
    val reply = jlist("flags", jlist("on"), "commands", null)
    assert(AclDecoder.decodeUser(reply).left.exists(_.isInstanceOf[AclError.DecodingFailure]))
  }

  test("decodeUser fails when a selector is not an array") {
    val reply = jlist("flags", jlist("on"), "selectors", jlist("not-an-array"))
    assert(AclDecoder.decodeUser(reply).left.exists(_.isInstanceOf[AclError.DecodingFailure]))
  }

  test("decodeLog renders scalar values (strings, numbers, booleans) to text") {
    val entries = new java.util.ArrayList[java.util.Map[String, Object]]()
    val _ = entries.add(jmap("count" -> Long.box(3), "reason" -> "auth", "enabled" -> Boolean.box(true)))
    val expected: Either[AclError, List[Map[String, String]]] =
      Right(List(Map("count" -> "3", "reason" -> "auth", "enabled" -> "true")))
    assertEquals(AclDecoder.decodeLog(entries), expected)
  }

  test("decodeLog fails on an unexpected null value") {
    val entries = new java.util.ArrayList[java.util.Map[String, Object]]()
    val _ = entries.add(jmap("reason" -> null))
    assert(AclDecoder.decodeLog(entries).left.exists(_.isInstanceOf[AclError.DecodingFailure]))
  }
}
