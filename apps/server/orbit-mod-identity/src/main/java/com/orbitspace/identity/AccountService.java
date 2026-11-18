package com.orbitspace.identity;

import static com.orbitspace.kernel.KernelErrorCode.*;
import com.orbitspace.kernel.*;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class AccountService {
    record Profile(String userId, String email, String displayName, boolean emailVerified) {}
    private record User(String id, String email, String passwordHash, boolean verified) {}
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder passwords;
    private final String dummyHash;
    private final SessionService sessions;
    private final EmailSenderPort mail;
    private final String origin;

    AccountService(JdbcTemplate db, BCryptPasswordEncoder passwords, SessionService sessions,
            EmailSenderPort mail, @Value("${orbit.public-origin}") String origin) {
        this.db = db;
        this.passwords = passwords;
        this.dummyHash = passwords.encode(Secrets.token());
        this.sessions = sessions;
        this.mail = mail;
        this.origin = origin;
    }

    static String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }

    @Transactional
    public void register(String email, String password) {
        String normalized = normalize(email);
        String encoded = passwords.encode(Secrets.password(password));
        String display = normalized.substring(0, normalized.indexOf('@'));
        db.update("""
                INSERT INTO users(id,email,display_name,password_hash) VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE id=id
                """, Secrets.id(), normalized, display.substring(0,Math.min(120,display.length())), encoded);
        User user = findUser(normalized);
        if (!user.verified()) send(user, "VERIFY", encoded);
    }

    private User findUser(String email) {
        return db.query("SELECT id,email,password_hash,email_verified FROM users WHERE email=? FOR UPDATE",
                (rs,i) -> new User(rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4)), email)
                .stream().findFirst().orElse(null);
    }

    private void send(User user, String purpose, String pendingPassword) {
        String token = Secrets.token();
        db.update("DELETE FROM auth_challenges WHERE user_id=? AND purpose=?",user.id(),purpose);
        db.update("""
                INSERT INTO auth_challenges(token_hash,user_id,purpose,pending_password_hash,expires_at)
                VALUES (?,?,?,?,CURRENT_TIMESTAMP(6)+INTERVAL 30 MINUTE)
                """, Secrets.hash(token),user.id(),purpose,pendingPassword);
        boolean verify = purpose.equals("VERIFY");
        String page = verify ? "/verify-email" : "/reset-password";
        String subject = verify ? "验证 OrbitSpace 邮箱" : "重置 OrbitSpace 密码";
        mail.send(user.email(),subject,"请在 30 分钟内打开链接完成操作。如果这不是你的请求，可以忽略这封邮件。\n"
                + origin + page + "?token=" + token);
    }

    @Transactional
    public void resetRequest(String email) {
        var user = findUser(normalize(email));
        if (user != null && user.verified()) send(user,"RESET",null);
    }

    @Transactional
    public void consume(String token, String newPassword) {
        String hash = Secrets.hash(token);
        String purpose = newPassword == null ? "VERIFY" : "RESET";
        String userId = db.query("SELECT user_id FROM auth_challenges WHERE token_hash=? AND purpose=?",
                (rs,i) -> rs.getString(1),hash,purpose).stream().findFirst().orElseThrow(() -> new DomainException(INVALID_REQUEST));
        // Login, password changes and challenge replacement all serialize on this user.
        db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",String.class,userId);
        var challenge = db.query("""
                SELECT COALESCE(pending_password_hash,'') FROM auth_challenges
                WHERE token_hash=? AND purpose=? AND expires_at>CURRENT_TIMESTAMP(6) FOR UPDATE
                """, (rs,i) -> rs.getString(1), hash, purpose).stream().findFirst().orElseThrow(() -> new DomainException(INVALID_REQUEST));
        db.update("DELETE FROM auth_challenges WHERE user_id=? AND purpose=?",userId,purpose);
        if (newPassword == null) {
            db.update("UPDATE users SET email_verified=TRUE,password_hash=? WHERE id=?",challenge,userId);
        } else {
            db.update("UPDATE users SET password_hash=? WHERE id=?",passwords.encode(Secrets.password(newPassword)),userId);
            sessions.revokeAll(userId);
        }
    }

    @Transactional
    public SessionService.Login login(String email, String password, String deviceLabel) {
        var user = findUser(normalize(email));
        boolean valid = passwords.matches(Secrets.password(password),user == null ? dummyHash : user.passwordHash());
        if (!valid || user == null || !user.verified()) throw new DomainException(AUTH_UNAUTHORIZED);
        return sessions.start(user.id(),deviceLabel);
    }

    public Profile profile(String id) {
        return db.queryForObject("SELECT id,email,display_name,email_verified FROM users WHERE id=?",
                (rs,i) -> new Profile(rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4)),id);
    }

    @Transactional
    public Profile rename(String id, String name) {
        db.update("UPDATE users SET display_name=? WHERE id=?",name.strip(),id);
        return profile(id);
    }
}
