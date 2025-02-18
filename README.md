redis4cats
==========

[![CI Status](https://github.com/profunktor/redis4cats/workflows/Scala/badge.svg)](https://github.com/profunktor/redis4cats/actions)
[![Gitter Chat](https://badges.gitter.im/profunktor-dev/redis4cats.svg)](https://gitter.im/profunktor-dev/redis4cats)
[![Maven Central](https://img.shields.io/maven-central/v/dev.profunktor/redis4cats-effects_2.12.svg)](http://search.maven.org/#search%7Cga%7C1%7Credis4cats-effects) <a href="https://typelevel.org/cats/"><img src="https://raw.githubusercontent.com/typelevel/cats/c23130d2c2e4a320ba4cde9a7c7895c6f217d305/docs/src/main/resources/microsite/img/cats-badge.svg" height="40px" align="right" alt="Cats friendly" /></a>
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-brightgreen.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

Redis client built on top of [Cats Effect](https://typelevel.org/cats-effect/), [Fs2](http://fs2.io/) and the async Java client [Lettuce](https://lettuce.io/).

### Quick Start

```scala
import cats.effect.*
import cats.implicits.*
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log.Stdout.given

object QuickStart extends IOApp.Simple:

  val run: IO[Unit] =
    Redis[IO].utf8("redis://localhost").use { redis =>
      for
        _ <- redis.set("foo", "123")
        x <- redis.get("foo")
        _ <- redis.setNx("foo", "should not happen")
        y <- redis.get("foo")
        _ <- IO(println(x === y)) // true
      yield ()
    }
```

The API is quite stable and *heavily used in production*. However, binary compatibility is not guaranteed across versions for now.

If you like it, give it a ⭐ ! If you think we could do better, please [let us know](https://gitter.im/profunktor-dev/redis4cats)!

### Versions

The `1.x.x` series is built on Cats Effect 3 whereas the `0.x.x` series is built on Cats Effect 2.

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

If you are trying to run cluster mode tests on macOS you might receive host not found errors. As a workaround add
new environment variable in `docker-compose.yml` for `RedisCluster`: `IP=0.0.0.0`

The environment section should look like this:
```
    environment:
      - INITIAL_PORT=30001
      - DEBUG=false
      - IP=0.0.0.0
```

## Code of Conduct

See the [Code of Conduct](https://redis4cats.profunktor.dev/CODE_OF_CONDUCT)

## Contribution Guidelines

Going forward , features must be compatible with the [Valkey OSS project](https://valkey.io/). 

## LICENSE

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this project except in compliance with
the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
language governing permissions and limitations under the License.
