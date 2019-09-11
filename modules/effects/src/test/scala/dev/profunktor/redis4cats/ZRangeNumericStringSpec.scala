/*
 * Copyright 2018-2019 ProfunKtor
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

import dev.profunktor.redis4cats.effects.ZRange
import org.scalatest.flatspec.AnyFlatSpec
import dev.profunktor.redis4cats.interpreter.RedisConversionOps

class ZRangeNumericStringSpec extends AnyFlatSpec with RedisConversionOps {

  implicit val stringNumeric: Numeric[String] = new Numeric[String] {

    private def d(s: String)                         = toDouble(s)
    override def plus(x: String, y: String): String  = (d(x) + d(y)).toString
    override def minus(x: String, y: String): String = (d(x) - d(y)).toString
    override def times(x: String, y: String): String = (d(x) * d(y)).toString
    override def negate(x: String): String           = (-d(x)).toString
    override def fromInt(x: Int): String             = x.toString
    override def toInt(x: String): Int               = Integer.parseInt(x)
    override def toLong(x: String): Long             = java.lang.Long.parseLong(x)
    override def toFloat(x: String): Float           = java.lang.Float.parseFloat(x)
    override def toDouble(x: String): Double         = java.lang.Double.parseDouble(x)
    override def compare(x: String, y: String): Int  = d(x) compare d(y)
  }

  "ZRange" should "not throw on Numeric[String]" in {
    ZRange("4", "20").asJavaRange
  }
  it should "not throw on Numeric[Double]" in {
    ZRange(4d, 20d).asJavaRange
  }
}
