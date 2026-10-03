package com.apop.fraud.redis;

import com.apop.fraud.scoring.FraudScorer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class RedisWindows {

    private static final String SLIDING = """
            local zset = KEYS[1]
            local hash = KEYS[2]
            local score = tonumber(ARGV[1])
            local now = tonumber(ARGV[2])
            local window = tonumber(ARGV[3])
            local member = ARGV[4]
            local amount = ARGV[5]
            local cutoff = now - window
            redis.call('ZADD', zset, score, member)
            redis.call('HSET', hash, member, amount)
            redis.call('ZREMRANGEBYSCORE', zset, '-inf', cutoff)
            local members = redis.call('ZRANGE', zset, 0, -1)
            local live = {}
            for _, m in ipairs(members) do live[m] = true end
            local keys = redis.call('HKEYS', hash)
            for _, m in ipairs(keys) do
              if not live[m] then redis.call('HDEL', hash, m) end
            end
            local sum = 0
            for _, m in ipairs(members) do
              local value = redis.call('HGET', hash, m)
              if value then sum = sum + tonumber(value) end
            end
            redis.call('PEXPIRE', zset, window + 3600000)
            redis.call('PEXPIRE', hash, window + 3600000)
            return #members .. ':' .. sum
            """;

    private static final String DISTINCT = """
            local zset = KEYS[1]
            local hash = KEYS[2]
            local score = tonumber(ARGV[1])
            local now = tonumber(ARGV[2])
            local window = tonumber(ARGV[3])
            local member = ARGV[4]
            local debtor = ARGV[5]
            local cutoff = now - window
            redis.call('ZADD', zset, score, member)
            redis.call('HSET', hash, member, debtor)
            redis.call('ZREMRANGEBYSCORE', zset, '-inf', cutoff)
            local members = redis.call('ZRANGE', zset, 0, -1)
            local live = {}
            for _, m in ipairs(members) do live[m] = true end
            local keys = redis.call('HKEYS', hash)
            for _, m in ipairs(keys) do
              if not live[m] then redis.call('HDEL', hash, m) end
            end
            local seen = {}
            local count = 0
            for _, m in ipairs(members) do
              local who = redis.call('HGET', hash, m)
              if who and not seen[who] then
                seen[who] = true
                count = count + 1
              end
            end
            redis.call('PEXPIRE', zset, window + 3600000)
            redis.call('PEXPIRE', hash, window + 3600000)
            return count
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<String> sliding;
    private final DefaultRedisScript<Long> distinct;

    public RedisWindows(StringRedisTemplate redis) {
        this.redis = redis;
        this.sliding = new DefaultRedisScript<>();
        this.sliding.setScriptText(SLIDING);
        this.sliding.setResultType(String.class);
        this.distinct = new DefaultRedisScript<>();
        this.distinct.setScriptText(DISTINCT);
        this.distinct.setResultType(Long.class);
    }

    public boolean warmed(UUID debtorAccountId) {
        return Boolean.TRUE.equals(redis.hasKey(warmupKey(debtorAccountId)));
    }

    public void markWarm(UUID debtorAccountId) {
        redis.opsForValue().set(warmupKey(debtorAccountId), "1");
    }

    public void remember(Prior prior, Instant now, int windowSeconds) {
        long score = prior.occurredAt().toEpochMilli();
        long clock = now.toEpochMilli();
        slide("vel:" + prior.debtorAccountId() + ":" + windowSeconds, score, clock, windowSeconds, prior.paymentId(), prior.amountMinor());
        slide("vel:" + prior.debtorAccountId() + ":" + prior.creditorHash() + ":" + windowSeconds, score, clock, windowSeconds, prior.paymentId(), prior.amountMinor());
        if (prior.deviceHash() != null) {
            slide("vel:dev:" + prior.deviceHash() + ":" + windowSeconds, score, clock, windowSeconds, prior.paymentId(), prior.amountMinor());
            distinctDebtors(prior.deviceHash(), windowSeconds, score, clock, prior.paymentId(), prior.debtorAccountId());
        }
    }

    public FraudScorer.Signals.Window readAndAdd(
            UUID debtorAccountId,
            String creditorHash,
            String deviceHash,
            UUID paymentId,
            long amountMinor,
            Instant now,
            int windowSeconds,
            String windowName) {
        long clock = now.toEpochMilli();
        long[] debtor = slide("vel:" + debtorAccountId + ":" + windowSeconds, clock, clock, windowSeconds, paymentId, amountMinor);
        long[] pair = slide("vel:" + debtorAccountId + ":" + creditorHash + ":" + windowSeconds, clock, clock, windowSeconds, paymentId, amountMinor);
        int deviceCount = 0;
        long deviceAmount = 0;
        int distinct = 0;
        if (deviceHash != null) {
            long[] device = slide("vel:dev:" + deviceHash + ":" + windowSeconds, clock, clock, windowSeconds, paymentId, amountMinor);
            deviceCount = (int) device[0];
            deviceAmount = device[1];
            distinct = distinctDebtors(deviceHash, windowSeconds, clock, clock, paymentId, debtorAccountId);
        }
        return new FraudScorer.Signals.Window(windowName, (int) debtor[0], debtor[1], (int) pair[0], pair[1], deviceCount, deviceAmount, distinct);
    }

    public boolean seenCreditor(UUID debtorAccountId, String creditorHash) {
        return Boolean.TRUE.equals(redis.hasKey("ben:" + debtorAccountId + ":" + creditorHash));
    }

    public boolean seenDevice(UUID debtorAccountId, String deviceHash) {
        return deviceHash != null && Boolean.TRUE.equals(redis.hasKey("devseen:" + debtorAccountId + ":" + deviceHash));
    }

    public boolean seenCountry(UUID debtorAccountId, String country) {
        return country != null && Boolean.TRUE.equals(redis.hasKey("geoseen:" + debtorAccountId + ":" + country));
    }

    public String[] previousSession(UUID debtorAccountId) {
        String value = redis.opsForValue().get("geo:" + debtorAccountId);
        if (value == null || !value.contains("|")) {
            return null;
        }
        return value.split("\\|", 2);
    }

    public void markApproved(UUID debtorAccountId, String creditorHash, String deviceHash, String sessionCountry, Duration lookback) {
        redis.opsForValue().set("ben:" + debtorAccountId + ":" + creditorHash, "1", Duration.ofDays(180));
        if (deviceHash != null) {
            redis.opsForValue().set("devseen:" + debtorAccountId + ":" + deviceHash, "1", lookback);
        }
        if (sessionCountry != null) {
            redis.opsForValue().set("geoseen:" + debtorAccountId + ":" + sessionCountry, "1", lookback);
        }
    }

    public void rememberSession(UUID debtorAccountId, String sessionCountry, Instant now, Duration lookback) {
        if (sessionCountry == null) {
            return;
        }
        redis.opsForValue().set("geo:" + debtorAccountId, sessionCountry + "|" + now.toEpochMilli(), lookback);
    }

    private long[] slide(String prefix, long score, long now, int windowSeconds, UUID paymentId, long amount) {
        String result = redis.execute(
                sliding,
                List.of(prefix, prefix + ":amt"),
                Long.toString(score),
                Long.toString(now),
                Long.toString(windowSeconds * 1000L),
                paymentId.toString(),
                Long.toString(amount));
        String[] parts = result.split(":");
        return new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])};
    }

    private int distinctDebtors(String deviceHash, int windowSeconds, long score, long now, UUID paymentId, UUID debtorAccountId) {
        String prefix = "devdebtors:" + deviceHash + ":" + windowSeconds;
        Long count = redis.execute(
                distinct,
                List.of(prefix, prefix + ":who"),
                Long.toString(score),
                Long.toString(now),
                Long.toString(windowSeconds * 1000L),
                paymentId.toString(),
                debtorAccountId.toString());
        return count == null ? 0 : count.intValue();
    }

    private static String warmupKey(UUID debtorAccountId) {
        return "warmup:" + debtorAccountId;
    }

    public record Prior(UUID paymentId, UUID debtorAccountId, String creditorHash, String deviceHash, long amountMinor, Instant occurredAt) {
    }
}
