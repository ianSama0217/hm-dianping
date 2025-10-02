-- 參數列表
-- 優惠券id
local voucherId = ARGV[1]
-- 用戶id
local userId = ARGV[2]

-- 數據key
-- 庫存key
local stockKey = 'seckill:stock:' .. voucherId
-- 訂單key
local orderKey = 'seckill:order:' .. voucherId

-- script
-- 1. 檢查庫存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 庫存不足
    return 1
end

-- 2. 檢查用戶是否下過單
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 用戶已經下過單
    return 2
end

-- 3. 扣減庫存
redis.call('incrby', stockKey, -1)

-- 4. 記錄用戶下單
redis.call('sadd', orderKey, userId)