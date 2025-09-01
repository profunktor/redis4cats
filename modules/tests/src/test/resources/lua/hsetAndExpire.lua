local key = KEYS[1]
local field = ARGV[1]
local value = ARGV[2]
local ttl = tonumber(ARGV[3])

local numFieldsSet = redis.call('hset', key, field, value)
redis.call('expire', key, ttl)
return numFieldsSet
