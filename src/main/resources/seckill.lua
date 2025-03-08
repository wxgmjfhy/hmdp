local voucherId = ARGV[1]
local userId = ARGV[2]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 检查库存
if (tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end

-- 检查是否重复下单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 减少库存
redis.call('incrby', stockKey, -1)

-- 用户下单记录
redis.call('sadd', orderKey, userId)

return 0