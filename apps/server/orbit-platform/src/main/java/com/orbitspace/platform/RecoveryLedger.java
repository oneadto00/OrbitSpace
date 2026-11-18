package com.orbitspace.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.RevocationLedgerPort;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * MySQL contains a projection and a replay cursor. A separately retained private S3 bucket owns the
 * ordered records and conditional high-water mark. Never restore that bucket from a business
 * snapshot. Missing history or an unhandled replay aborts startup before traffic is admitted.
 */
public class RecoveryLedger implements RevocationLedgerPort {
    private static final String GENESIS = "0".repeat(64);

    private record Head(String ledgerId, long sequence, String digest) {}

    private record Remote(Head head, String etag) {}

    private record Record(
            String ledgerId, long sequence, String previousDigest, Revocation revocation) {}

    private record Encoded(Record value, String digest) {}

    private record State(String ledgerId, boolean initialized, long applied) {}

    private final JdbcTemplate db;
    private final S3Client s3;
    private final String bucket;
    private final String prefix;
    private final ObjectMapper mapper;
    private final org.springframework.beans.factory.ListableBeanFactory beans;

    public RecoveryLedger(
            JdbcTemplate db,
            S3Client s3,
            String bucket,
            String prefix,
            ObjectMapper mapper,
            org.springframework.beans.factory.ListableBeanFactory beans) {
        this.db = db;
        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = prefix.endsWith("/") ? prefix : prefix + "/";
        this.mapper = mapper;
        this.beans = beans;
    }

    private State lock() {
        return db.queryForObject(
                "SELECT ledger_id,initialized,applied_sequence FROM revocation_ledger_state WHERE"
                    + " id=1 FOR UPDATE",
                (rs, i) -> new State(rs.getString(1), rs.getBoolean(2), rs.getLong(3)));
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException(
                "Revocation journal could not certify the recovery boundary");
    }

    private String key(long sequence) {
        return prefix + "entries/" + String.format(Locale.ROOT, "%019d", sequence);
    }

    private Remote head() {
        var bytes = s3.getObjectAsBytes(b -> b.bucket(bucket).key(prefix + "head"));
        Head head = PlatformValueSupport.decode(mapper, bytes.asUtf8String(), Head.class);
        if (head.sequence() < 0 || !head.digest().matches("[0-9a-f]{64}")) throw unavailable();
        return new Remote(head, bytes.response().eTag());
    }

    private void createOnce(String key, String value) {
        try {
            s3.putObject(
                    b -> b.bucket(bucket).key(key).ifNoneMatch("*"), RequestBody.fromString(value));
        } catch (S3Exception e) {
            if (e.statusCode() != 412) throw e;
        }
    }

    private void initialize(State state) {
        // Never recreate a missing authority for an already-enrolled business database.
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (S3Exception e) {
            if (e.statusCode() != 404) throw e;
            s3.createBucket(b -> b.bucket(bucket));
        }
        createOnce(
                prefix + "head", PlatformValueSupport.json(mapper, new Head(state.ledgerId, 0, GENESIS)));
    }

    private Encoded entry(long sequence) {
        var bytes = s3.getObjectAsBytes(b -> b.bucket(bucket).key(key(sequence))).asByteArray();
        Record rec =
                PlatformValueSupport.decode(
                        mapper, new String(bytes, StandardCharsets.UTF_8), Record.class);
        if (rec.sequence() != sequence) throw unavailable();
        return new Encoded(rec, PlatformValueSupport.digest(bytes));
    }

    private Remote advance(Remote previous, Encoded next) {
        Head old = previous.head;
        if (!next.value.ledgerId.equals(old.ledgerId)
                || !next.value.previousDigest.equals(old.digest)
                || next.value.sequence != old.sequence + 1) throw unavailable();
        Head updated = new Head(old.ledgerId, old.sequence + 1, next.digest);
        var response =
                s3.putObject(
                        b -> b.bucket(bucket).key(prefix + "head").ifMatch(previous.etag),
                        RequestBody.fromString(PlatformValueSupport.json(mapper, updated)));
        return new Remote(updated, response.eTag());
    }

    private Remote authority(State state) {
        if (!state.initialized) initialize(state);
        Remote current = head();
        if (!state.ledgerId.equals(current.head.ledgerId) || current.head.sequence < state.applied)
            throw unavailable();
        // Repair a crash between writing the next immutable entry and advancing the manifest.
        Encoded pending;
        try {
            pending = entry(current.head.sequence + 1);
        } catch (S3Exception e) {
            if (e.statusCode() != 404) throw e;
            return current;
        }
        return advance(current, pending);
    }

    @Transactional
    public void verifyAndReplay() {
        State state = lock();
        Head remote = authority(state).head;
        String digest = GENESIS;
        for (long n = 1; n <= remote.sequence; n++) {
            Encoded entry = entry(n);
            if (!entry.value.ledgerId.equals(state.ledgerId)
                    || !entry.value.previousDigest.equals(digest)) throw unavailable();
            digest = entry.digest;
            if (n <= state.applied) {
                if (!matchesProjection(n, entry)) throw unavailable();
            }
            // Selective restores may roll back only a domain table, leaving this SQL projection
            // intact.
            // Every handler is monotonic and scopes the revocation to its original grant/version.
            replay(new Entry(n, entry.value.revocation));
            if (n > state.applied) mirror(entry);
        }
        if (!remote.digest.equals(digest)) throw unavailable();
        db.update("UPDATE revocation_ledger_state SET initialized=TRUE WHERE id=1");
    }

    private boolean matchesProjection(long sequence, Encoded entry) {
        Revocation r = entry.value.revocation;
        return Boolean.TRUE.equals(
                db.queryForObject(
                        """
                        SELECT EXISTS(SELECT 1 FROM revocation_purge_records WHERE sequence_no=? AND event_id=? AND
                            target_type=? AND space_id=? AND target_id=? AND target_version=? AND effective_at=? AND entry_digest=?)
                        """,
                        Boolean.class,
                        sequence,
                        r.eventId(),
                        r.target().name(),
                        r.spaceId(),
                        r.targetId(),
                        r.version(),
                        PlatformValueSupport.time(r.effectiveAt()),
                        entry.digest));
    }

    @SuppressWarnings("unchecked")
    private void replay(Entry entry) {
        // Each owning domain supplies a Consumer<Entry> bean named orbitReplay + the kernel target.
        String name = "orbitReplay" + entry.revocation().target().name();
        if (!beans.containsBean(name))
            throw new IllegalStateException(
                    "Missing recovery handler for " + entry.revocation().target());
        ((Consumer<Entry>) beans.getBean(name, Consumer.class)).accept(entry);
    }

    private void mirror(Encoded entry) {
        Record rec = entry.value;
        Revocation r = rec.revocation;
        db.update(
                """
                INSERT INTO revocation_purge_records(sequence_no,event_id,target_type,space_id,target_id,
                    target_version,effective_at,entry_digest) VALUES (?,?,?,?,?,?,?,?)
                """,
                rec.sequence,
                r.eventId(),
                r.target().name(),
                r.spaceId(),
                r.targetId(),
                r.version(),
                PlatformValueSupport.time(r.effectiveAt()),
                entry.digest);
        db.update(
                "UPDATE revocation_ledger_state SET initialized=TRUE,applied_sequence=? WHERE id=1",
                rec.sequence);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long append(Revocation supplied) {
        Revocation r =
                new Revocation(
                        supplied.eventId(),
                        supplied.target(),
                        supplied.spaceId(),
                        supplied.targetId(),
                        supplied.version(),
                        supplied.effectiveAt().truncatedTo(ChronoUnit.MICROS));
        State state = lock();
        Remote remote = authority(state);
        // A prior business transaction may have rolled back after independent publication. Apply
        // the
        // conservative revocation before the next append. Recovery handlers must be idempotent.
        for (long n = state.applied + 1; n <= remote.head.sequence; n++) {
            Encoded e = entry(n);
            if (!e.value.revocation.equals(r)) replay(new Entry(n, e.value.revocation));
            mirror(e);
        }
        List<Long> existing =
                db.queryForList(
                        "SELECT sequence_no FROM revocation_purge_records WHERE event_id=? FOR"
                            + " UPDATE",
                        Long.class,
                        r.eventId());
        if (!existing.isEmpty()) {
            Encoded previous = entry(existing.getFirst());
            if (!previous.value.revocation.equals(r))
                throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
            return previous.value.sequence;
        }
        Record value = new Record(state.ledgerId, remote.head.sequence + 1, remote.head.digest, r);
        String serialized = PlatformValueSupport.json(mapper, value);
        // Exercise all SQL constraints before irrevocably extending the independent authority.
        // This projection becomes visible only when the caller commits after successful
        // publication.
        Encoded proposed = new Encoded(value, PlatformValueSupport.digest(serialized));
        mirror(proposed);
        createOnce(key(value.sequence), serialized);
        Encoded stored = entry(value.sequence);
        if (!stored.equals(proposed)) throw unavailable();
        advance(remote, stored);
        return value.sequence;
    }

    @Override
    @Transactional
    public long verifiedHighWaterMark() {
        State state = lock();
        Head verified = authority(state).head;
        String digest = GENESIS;
        for (long n = 1; n <= verified.sequence; n++) {
            Encoded e = entry(n);
            if (!e.value.ledgerId.equals(state.ledgerId) || !e.value.previousDigest.equals(digest))
                throw unavailable();
            digest = e.digest;
        }
        if (!verified.digest.equals(digest)) throw unavailable();
        return verified.sequence;
    }

    @Override
    @Transactional
    public List<Entry> readAfter(long after, long through, int limit) {
        if (after < 0 || through < after || limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Invalid journal range");
        State state = lock();
        Head verified = authority(state).head;
        if (through > verified.sequence) throw unavailable();
        List<Entry> batch = new ArrayList<>();
        String expected = after == 0 ? GENESIS : entry(after).digest;
        for (long n = after + 1; n <= through && batch.size() < limit; n++) {
            Encoded e = entry(n);
            if (!e.value.ledgerId.equals(state.ledgerId)
                    || !e.value.previousDigest.equals(expected)) throw unavailable();
            expected = e.digest;
            batch.add(new Entry(n, e.value.revocation));
        }
        return List.copyOf(batch);
    }
}
