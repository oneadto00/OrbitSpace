package com.orbitspace.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.*;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.time.Duration;

@AutoConfiguration
@EnableScheduling
@EnableTransactionManagement(proxyTargetClass = true)
public class PlatformConfiguration {
    private static S3Client client(
            URI endpoint, String accessKey, String secretKey, String region) {
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .forcePathStyle(true)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(
                        c ->
                                c.apiCallTimeout(Duration.ofSeconds(60))
                                        .apiCallAttemptTimeout(Duration.ofSeconds(25)))
                .build();
    }

    @Bean
    S3Client contentClient(
            @Value("${orbit.s3.endpoint}") URI endpoint,
            @Value("${orbit.s3.access-key}") String key,
            @Value("${orbit.s3.secret-key}") String secret,
            @Value("${orbit.s3.region:us-east-1}") String region) {
        return client(endpoint, key, secret, region);
    }

    @Bean
    S3Client recoveryClient(
            @Value("${ORBIT_RECOVERY_ENDPOINT:${orbit.s3.endpoint}}") URI endpoint,
            @Value("${ORBIT_RECOVERY_ACCESS_KEY:${orbit.s3.access-key}}") String key,
            @Value("${ORBIT_RECOVERY_SECRET_KEY:${orbit.s3.secret-key}}") String secret,
            @Value("${ORBIT_RECOVERY_REGION:${orbit.s3.region:us-east-1}}") String region) {
        return client(endpoint, key, secret, region);
    }

    @Bean
    RedisSecurityStore redisSecurityStore(StringRedisTemplate redis, ObjectMapper mapper) {
        return new RedisSecurityStore(redis, mapper);
    }

    @Bean
    S3ObjectStore objectStorage(
            JdbcTemplate db,
            PlatformTransactionManager tx,
            @Qualifier("contentClient") S3Client s3,
            @Value("${orbit.s3.bucket}") String bucket,
            @Value("${orbit.platform.object-grace}") Duration grace) {
        return new S3ObjectStore(db, tx, s3, bucket, grace);
    }

    @Bean
    OutboxRelay outboxRelay(
            JdbcTemplate db,
            PlatformTransactionManager tx,
            StringRedisTemplate redis,
            ObjectMapper mapper,
            ObjectProvider<OutboxEventHandler> handlers) {
        return new OutboxRelay(db, new TransactionTemplate(tx), redis, mapper, handlers);
    }

    @Bean
    JdbcJobs asyncJobs(JdbcTemplate db, OutboxPublisherPort outbox) {
        return new JdbcJobs(db, outbox);
    }

    @Bean
    JobRunner jobRunner(
            ObjectProvider<AsyncJobProcessor> processors,
            JdbcJobs jobs,
            StringRedisTemplate redis) {
        return new JobRunner(processors, jobs, redis);
    }

    @Bean
    @DependsOnDatabaseInitialization
    RecoveryLedger recoveryLedger(
            JdbcTemplate db,
            @Qualifier("recoveryClient") S3Client s3,
            @Value("${orbit.platform.recovery-bucket}") String bucket,
            @Value("${orbit.platform.recovery-prefix}") String prefix,
            ObjectMapper mapper,
            ListableBeanFactory beans) {
        return new RecoveryLedger(db, s3, bucket, prefix, mapper, beans);
    }

    @Bean
    SmartInitializingSingleton verifyRecoveryJournal(RecoveryLedger ledger) {
        return ledger::verifyAndReplay;
    }

    @Bean
    EmailSenderPort transactionalEmail(
            JavaMailSender mail, @Value("${orbit.platform.mail-from}") String from) {
        return (recipient, subject, body) -> {
            if (recipient.contains("\r")
                    || recipient.contains("\n")
                    || subject.contains("\r")
                    || subject.contains("\n"))
                throw new IllegalArgumentException("Invalid email headers");
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(recipient);
            message.setSubject(subject);
            message.setText(body);
            mail.send(message);
        };
    }

    @Bean
    AuditStore auditLog(JdbcTemplate db) {
        return new AuditStore(db);
    }

    public static class AuditStore implements AuditLogPort {
        private final JdbcTemplate db;

        AuditStore(JdbcTemplate db) {
            this.db = db;
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public void append(Entry e) {
            db.update(
                    "INSERT INTO"
                        + " audit_logs(request_id,actor_id,action,resource_id,outcome,created_at)"
                        + " VALUES (?,?,?,?,?,?)",
                    safeId(e.requestId()),
                    safeId(e.actorId()),
                    auditCode(e.action()),
                    safeId(e.resourceId()),
                    auditCode(e.outcome()),
                    PlatformValues.time(e.occurredAt()));
        }

        private static String safeId(String id) {
            if (id == null) return null;
            // UUIDs and numeric IDs are safe to retain. Unknown user-controlled identifiers are
            // pseudonymized.
            if (id.matches("(?i)[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                    || id.matches("[0-9]{1,20}")) return id;
            return "sha256-" + PlatformValues.digest(id);
        }

        private static String auditCode(String code) {
            if (code == null || !code.matches("[A-Z][A-Z_.]{0,47}")) return "REDACTED";
            return code;
        }
    }
}
