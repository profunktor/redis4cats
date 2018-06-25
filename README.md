fs2-redis
=========

[![Build Status](https://travis-ci.org/gvolpe/fs2-redis.svg?branch=master)](https://travis-ci.org/gvolpe/fs2-redis)
[![codecov](https://codecov.io/gh/gvolpe/fs2-redis/branch/master/graph/badge.svg)](https://codecov.io/gh/gvolpe/fs2-redis)

Stream-based client built on top of [Fs2](https://functional-streams-for-scala.github.io/fs2/) and the async java client [Lettuce](https://lettuce.io/).

:warning: This project is under development and only includes a limited set of features :warning:

`fs2-redis` defines two types of API: one Stream-based using [Fs2](https://functional-streams-for-scala.github.io/fs2/) and another Effect-based using [Cats Effect](https://typelevel.org/cats-effect/).

### Streams

- [x] [PubSub API](https://redis.io/topics/pubsub) implemented on top of `fs2` streams.
- [x] [Streams API](https://redis.io/topics/streams-intro) experimental API, subject to changes (WIP).
  + High-level API offers `read` and `append` using the underlying commands `XREAD` and `XADD` respectively.
  + Consumer Groups are yet not implemented.

### Effects

- [ ] [Geo API](https://redis.io/commands#geo): `geoadd`, `geohash`, `geopos`, `geodist`, etc.
- [x] [Hashes API](https://redis.io/commands#hash): `hgetall`, `hset`, `hdel`, `hincrby`, etc.
- [ ] [Lists API](https://redis.io/commands#list): `rpush`, `lrange`, `lpop`, etc.
- [x] [Sets API](https://redis.io/commands#set): `sadd`, `scard`, `srem`, `spop`, etc.
- [ ] [Sorted Sets API](https://redis.io/commands#sorted_set): `zcount`, `zcard`, `zrangebyscore`, `zrank`, etc.
- [x] [Strings API](https://redis.io/commands#string): `get`, `set`, `del`, `expire`, etc (includes some generic methods).

Other features are not considered at the moment but PRs and suggestions are very welcome.

## LICENSE

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this project except in compliance with
the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
language governing permissions and limitations under the License.
