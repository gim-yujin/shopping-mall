-- 재고 원자 차감 (CAS 의미론).
--
-- KEYS[1] = 재고 키 (예: "stock:product:42", "fs:item:7")
-- ARGV[1] = 차감 수량 (양의 정수)
--
-- 반환값:
--   >= 0  : 차감 후 남은 재고
--   -1    : 재고 부족 (현재값 < 차감량)
--   -2    : 키가 존재하지 않음 (Preloader 누락 / 알 수 없는 상품)
--
-- Redis는 단일 스레드이므로 본 스크립트 전체가 atomic 으로 실행된다.
-- WATCH / MULTI / EXEC 없이도 race 없음.
local cur = redis.call('GET', KEYS[1])
if cur == false then
    return -2
end
local curN = tonumber(cur)
local qty = tonumber(ARGV[1])
if curN < qty then
    return -1
end
return redis.call('DECRBY', KEYS[1], qty)
