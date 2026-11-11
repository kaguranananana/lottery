-- 同一 Redis 时钟下计算两个令牌桶，拒绝时两个桶都不扣费。
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local function refill(key, rate, capacity)
    local raw = redis.call('GET', key)
    if not raw then
        return capacity, now
    end
    local tokens, previous = string.match(raw, '^([^ ]+) (%d+)$')
    previous = tonumber(previous)
    local at = math.max(previous, now)
    return math.min(capacity, tonumber(tokens) + (at - previous) * rate / 1000), at
end

local globalRate, globalCapacity = tonumber(ARGV[1]), tonumber(ARGV[2])
local userRate, userCapacity = tonumber(ARGV[3]), tonumber(ARGV[4])
-- 用户已经耗尽时，省去读取全局桶。全局拒绝也不消耗用户配额。
local user, userAt = refill(KEYS[2], userRate, userCapacity)
if user < 1 then return 2 end
local global, globalAt = refill(KEYS[1], globalRate, globalCapacity)
if global < 1 then return 1 end

local function spend(key, tokens, at, rate, capacity)
    -- 满桶所需时间后状态等价于初始状态，自动回收闲置键。
    redis.call('SET', key, string.format('%.17g %.0f', tokens - 1, at),
        'PX', math.ceil(capacity * 1000 / rate))
end
spend(KEYS[1], global, globalAt, globalRate, globalCapacity)
spend(KEYS[2], user, userAt, userRate, userCapacity)
return 0
