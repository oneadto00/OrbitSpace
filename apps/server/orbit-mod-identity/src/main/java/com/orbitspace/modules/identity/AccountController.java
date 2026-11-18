package com.orbitspace.modules.identity;

import com.orbitspace.kernel.AuthenticatedPrincipal;
import com.orbitspace.security.RefreshCsrfTokenRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AccountController {
    record Credentials(@NotBlank @Email @Size(max=254) String email, @NotEmpty @Size(max=72) String password) {}
    record EmailOnly(@NotBlank @Email @Size(max=254) String email) {}
    record Token(@NotBlank @Size(max=256) String token) {}
    record Reset(@NotBlank @Size(max=256) String token, @NotEmpty @Size(max=72) String newPassword) {}
    record Rename(@NotBlank @Size(max=120) String displayName) {}
    private final AccountService accounts;
    private final SessionService sessions;
    private final IdentityRateLimitService limits;
    private final boolean secureCookie;
    AccountController(AccountService accounts,SessionService sessions,IdentityRateLimitService limits,
            @Value("${orbit.auth.secure-cookie:true}") boolean secureCookie) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.limits = limits;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/register") @ResponseStatus(HttpStatus.ACCEPTED)
    void register(@Valid @RequestBody Credentials body, HttpServletRequest req, HttpServletResponse res) {
        limits.apply("register",req,res,20,AccountService.normalize(body.email),3);
        accounts.register(body.email,body.password);
    }

    @PostMapping("/verify-email") @ResponseStatus(HttpStatus.NO_CONTENT)
    void verify(@Valid @RequestBody Token body,HttpServletRequest req,HttpServletResponse res) {
        limits.apply("verify",req,res,30,null,0);
        accounts.consume(body.token,null);
    }

    @PostMapping("/password-reset/request") @ResponseStatus(HttpStatus.ACCEPTED)
    void requestReset(@Valid @RequestBody EmailOnly body,HttpServletRequest req,HttpServletResponse res) {
        limits.apply("reset-request",req,res,15,AccountService.normalize(body.email),3);
        accounts.resetRequest(body.email);
    }

    @PostMapping("/password-reset/confirm") @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(@Valid @RequestBody Reset body,HttpServletRequest req,HttpServletResponse res) {
        limits.apply("reset",req,res,20,null,0);
        accounts.consume(body.token,body.newPassword);
    }

    @PostMapping("/login")
    SessionService.AccessSession login(@Valid @RequestBody Credentials body,HttpServletRequest req,HttpServletResponse res) {
        limits.apply("login",req,res,40,AccountService.normalize(body.email),10);
        String label = req.getHeader("User-Agent");
        if (label == null || label.isBlank()) label = "Browser";
        label = label.substring(0,Math.min(label.length(),160));
        return response(accounts.login(body.email,body.password,label),res);
    }

    @GetMapping("/csrf")
    Map<String,String> csrf(CsrfToken token,HttpServletResponse res) {
        res.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        return Map.of("csrfToken",token.getToken());
    }

    @PostMapping("/refresh")
    SessionService.AccessSession refresh(HttpServletRequest req,HttpServletResponse res) {
        return response(sessions.rotate(RefreshCsrfTokenRepository.cookie(req)),res);
    }

    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@AuthenticationPrincipal AuthenticatedPrincipal account,HttpServletResponse res) {
        sessions.revoke(account.userId(),account.sessionId());
        setCookie(res,"",0);
    }

    @GetMapping("/sessions")
    List<SessionService.SessionInfo> sessions(@AuthenticationPrincipal AuthenticatedPrincipal account) {
        return sessions.list(account);
    }

    @DeleteMapping("/sessions/{sessionId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal AuthenticatedPrincipal account,@PathVariable String sessionId) {
        sessions.revoke(account.userId(),sessionId);
    }

    @GetMapping("/profile")
    AccountService.Profile profile(@AuthenticationPrincipal AuthenticatedPrincipal account) {
        return accounts.profile(account.userId());
    }

    @PatchMapping("/profile")
    AccountService.Profile rename(@AuthenticationPrincipal AuthenticatedPrincipal account,@Valid @RequestBody Rename body) {
        return accounts.rename(account.userId(),body.displayName);
    }

    private SessionService.AccessSession response(SessionService.Login login, HttpServletResponse res) {
        setCookie(res,login.refresh(),login.maxAge());
        return login.access();
    }

    private void setCookie(HttpServletResponse res,String value,long maxAge) {
        var cookie = ResponseCookie.from(RefreshCsrfTokenRepository.COOKIE,value).path("/api/v1/auth")
                .httpOnly(true).secure(secureCookie).sameSite("Strict").maxAge(Duration.ofSeconds(maxAge)).build();
        res.addHeader(HttpHeaders.SET_COOKIE,cookie.toString());
        res.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
    }
}
