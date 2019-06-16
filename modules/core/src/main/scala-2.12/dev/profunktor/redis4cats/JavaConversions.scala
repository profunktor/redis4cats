package dev.profunktor.redis4cats

import scala.collection.convert.{DecorateAsJava, DecorateAsScala}

object JavaConversions extends DecorateAsJava with DecorateAsScala
