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

import scala.collection.convert.{ AsJavaExtensions, AsScalaExtensions }

object JavaConversions extends AsJavaExtensions with AsScalaExtensions {

  // Lettuce reply elements are frequently a boxed java.lang.Long/Double that's null for a per-position
  // miss (e.g. a JSONPath that didn't match, or a GET pattern that resolved to nothing) - null-checking
  // the boxed reference via Option(...) before unboxing is the only safe order; unboxing first and
  // wrapping the result in Option afterwards defeats the null check.
  implicit class JLongOps(private val l: java.lang.Long) extends AnyVal {
    def toOption: Option[Long] = Option(l).map(Long.unbox)
  }

  implicit class JDoubleOps(private val d: java.lang.Double) extends AnyVal {
    def toOption: Option[Double] = Option(d).map(Double.unbox)
  }
}
