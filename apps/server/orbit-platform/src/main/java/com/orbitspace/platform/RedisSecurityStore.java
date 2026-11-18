package com.orbitspace.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.AtomicTokenStorePort;
import com.orbitspace.kernel.RateLimitPort;
import com.orbitspace.kernel.SpaceAuthorizationPort.Membership;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public class RedisSecurityStore implements RateLimitPort, AtomicTokenStorePort {
    private static final DefaultRedisScript<Long> ACQUIRE =
            script(
                    """
                    local time = redis.call('TIME')
                    local now = time[1]*1000 + math.floor(time[2]/1000)
                    local window = tonumber(ARGV[1])
                    redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now-window)
                    if redis.call('ZCARD',KEYS[1]) >= tonumber(ARGV[2]) then
                        local oldest = redis.call('ZRANGE',KEYS[1],0,0,'WITHSCORES')
                        return math.max(1,tonumber(oldest[2])+window-now)
                    end
                    redis.call('ZADD',KEYS[1],now,ARGV[3])
                    redis.call('PEXPIRE',KEYS[1],window)
                    return 0
                    """);
    // Fixed-width decimal versions compare correctly beyond Lua's exact integer range.
    private static final DefaultRedisScript<Long> CACHE =
            script(
                    """
                    local fence = redis.call('GET',KEYS[1])
                    if fence and ARGV[1] < fence then return 0 end
                    redis.call('SET',KEYS[1],ARGV[1])
                    redis.call('SET',KEYS[2],ARGV[2],'PX',ARGV[3])
                    return 1
                    """);
    private static final DefaultRedisScript<Long> INVALIDATE =
            script(
                    """
                    local fence = redis.call('GET',KEYS[1])
                    if fence and ARGV[1] < fence then return 0 end
                    redis.call('SET',KEYS[1],ARGV[1])
                    redis.call('DEL',KEYS[2])
                    return 1
                    """);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public RedisSecurityStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    private static DefaultRedisScript<Long> script(String text) {
        return new DefaultRedisScript<>(text, Long.class);
    }

    @Override
    public Decision tryAcquire(String key, int limit, Duration window) {
        if (limit < 1 || window.toMillis() < 1)
            throw new IllegalArgumentException("Invalid rate limit");
        long wait =
                redis.execute(
                        ACQUIRE,
                        List.of("orbit:rate:" + PlatformValues.digest(key)),
                        Long.toString(window.toMillis()),
                        Integer.toString(limit),
                        UUID.randomUUID().toString());
        return new Decision(wait == 0, Duration.ofMillis(wait));
    }

    @Override
    public String issueTicket(TicketGrant grant, Duration ttl) {
        if (grant == null
                || grant.userId() == null
                || grant.sessionId() == null
                || grant.spaceId() == null
                || grant.roomId() == null)
            throw new IllegalArgumentException("Incomplete ticket grant");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String key = "orbit:ticket:" + PlatformValues.digest(ticket);
        if (!Boolean.TRUE.equals(
                redis.opsForValue()
                        .setIfAbsent(
                                key,
                                PlatformValues.json(mapper, grant),
                                Duration.ofMillis(boundedTtl(ttl, 120_000)))))
            throw new IllegalStateException("Cannot issue ticket");
        return ticket;
    }

    @Override
    public Optional<TicketGrant> consumeTicket(String ticket) {
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        String grant =
                redis.opsForValue().getAndDelete("orbit:ticket:" + PlatformValues.digest(ticket));
        return Optional.ofNullable(grant)
                .map(s -> PlatformValues.decode(mapper, s, TicketGrant.class));
    }

    private static List<String> memberKeys(String spaceId, String userId) {
        String tag =
                "orbit:member:{"
                        + PlatformValues.digest(spaceId.length() + ":" + spaceId + userId)
                        + "}";
        return List.of(tag + ":version", tag + ":data");
    }

    @Override
    public Optional<Membership> findMembership(
            String spaceId, String userId, long epoch, long currentPermVersion) {
        String data = redis.opsForValue().get(memberKeys(spaceId, userId).get(1));
        return Optional.ofNullable(data)
                .map(s -> PlatformValues.decode(mapper, s, Membership.class))
                .filter(
                        m ->
                                m.spaceId().equals(spaceId)
                                        && m.userId().equals(userId)
                                        && m.membershipEpoch() == epoch
                                        && m.permVersion() == currentPermVersion);
    }

    private static String version(long version) {
        if (version < 0) throw new IllegalArgumentException("Invalid permission version");
        return String.format(Locale.ROOT, "%019d", version);
    }

    private static long boundedTtl(Duration ttl, long maxMillis) {
        if (ttl.toMillis() < 1) throw new IllegalArgumentException("Invalid expiration");
        return Math.min(ttl.toMillis(), maxMillis);
    }

    @Override
    public void cacheMembership(Membership membership, Duration ttl) {
        redis.execute(
                CACHE,
                memberKeys(membership.spaceId(), membership.userId()),
                version(membership.permVersion()),
                PlatformValues.json(mapper, membership),
                Long.toString(boundedTtl(ttl, 30_000)));
    }

    @Override
    public void invalidateMembership(String spaceId, String userId, long newPermVersion) {
        redis.execute(INVALIDATE, memberKeys(spaceId, userId), version(newPermVersion));
    }
}
