redis4cats
==========

[![CI Status](https://github.com/profunktor/redis4cats/workflows/Build/badge.svg)](https://github.com/profunktor/redis4cats/actions)
[![Gitter Chat](https://badges.gitter.im/profunktor-dev/redis4cats.svg)](https://gitter.im/profunktor-dev/redis4cats)
[![Maven Central](https://img.shields.io/maven-central/v/dev.profunktor/redis4cats-effects_2.12.svg)](http://search.maven.org/#search%7Cga%7C1%7Credis4cats-effects) <a href="https://typelevel.org/cats/"><img src="https://typelevel.org/cats/img/cats-badge.svg" height="40px" align="right" alt="Cats friendly" /></a>
[![MergifyStatus](https://img.shields.io/endpoint.svg?url=https://gh.mergify.io/badges/profunktor/redis4cats&style=flat)](https://mergify.io)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-brightgreen.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

Redis client built on top of [Cats Effect](https://typelevel.org/cats-effect/), [Fs2](http://fs2.io/) and the async Java client [Lettuce](https://lettuce.io/).

### Quick Start

```scala
import cats.effect._
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log.Stdout._

object QuickStart extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    Redis[IO].utf8("redis://localhost").use { cmd =>
      for {
        _ <- cmd.set("foo", "123")
        x <- cmd.get("foo")
        _ <- cmd.setNx("foo", "should not happen")
        y <- cmd.get("foo")
        _ <- IO(println(x === y)) // true
      } yield ExitCode.Success
    }

}
```

The API is quite stable and *heavily used in production*. However, binary compatibility won't be guaranteed until we reach `1.0.0`.

If you like it, give it a ⭐ ! If you think we could do better, please [let us know](https://gitter.im/profunktor-dev/redis4cats)!

### Dependencies

Add this to your `build.sbt` for the [Effects API](https://redis4cats.profunktor.dev/effects/) (depends on `cats-effect`):

```
libraryDependencies += "dev.profunktor" %% "redis4cats-effects" % Version
```

Add this for the [Streams API](https://redis4cats.profunktor.dev/streams/) (depends on `fs2` and `cats-effect`):

```
libraryDependencies += "dev.profunktor" %% "redis4cats-streams" % Version
```

### Log4cats support

`redis4cats` needs a logger for internal use and provides instances for `log4cats`. It is the recommended logging library:

```
libraryDependencies += "dev.profunktor" %% "redis4cats-log4cats" % Version
```

## Running the tests locally

Start both a single Redis node and a cluster using `docker-compose`:

```bash
> docker-compose up
> sbt +test
```

## Code of Conduct

See the [Code of Conduct](https://redis4cats.profunktor.dev/CODE_OF_CONDUCT)

## LICENSE

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this project except in compliance with
the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
language governing permissions and limitations under the License.
