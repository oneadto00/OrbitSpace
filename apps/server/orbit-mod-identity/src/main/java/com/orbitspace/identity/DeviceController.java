package com.orbitspace.identity;

import com.orbitspace.kernel.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/device")
public class DeviceController {
    record Code(@NotBlank @Size(max=32) String user_code) {}
    record Approval(@NotBlank @Size(max=32) String user_code, @NotBlank @Size(max=160) String spaceId,
            @NotBlank @Size(max=160) String projectId, @Positive long membershipEpoch,
            @NotEmpty @Size(max=2) List<@NotBlank String> scopes) {}
    private final DeviceService devices;
    private final RequestLimits limits;
    DeviceController(DeviceService devices, RequestLimits limits) { this.devices = devices; this.limits = limits; }

    @PostMapping(value="/authorize",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    DeviceService.Authorization authorize(@RequestParam("client_id") String client,@RequestParam("device_label") String label,
            @RequestParam(defaultValue="metadata:write") String scope,HttpServletRequest req,HttpServletResponse res) {
        limits.apply("device-authorize",req,res,30,null,0);
        if (!DeviceService.CLIENT.equals(client) || label.isBlank() || label.length()>160 || scope.length()>80)
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        res.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        return devices.request(label.strip(),scope);
    }

    @PostMapping("/lookup")
    DeviceService.Pending lookup(@AuthenticationPrincipal AuthenticatedPrincipal account,@Valid @RequestBody Code body,
            HttpServletRequest req,HttpServletResponse res) {
        limits.apply("device-lookup",req,res,60,account.userId(),15);
        res.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        return devices.lookup(body.user_code);
    }

    @PostMapping("/approve") @ResponseStatus(HttpStatus.NO_CONTENT)
    void approve(@AuthenticationPrincipal AuthenticatedPrincipal account,@Valid @RequestBody Approval body,
            HttpServletRequest req,HttpServletResponse res) {
        limits.apply("device-approve",req,res,60,account.userId(),15);
        devices.approve(account.userId(),body.user_code,body.spaceId,body.projectId,body.membershipEpoch,body.scopes);
    }

    @PostMapping(value="/token",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<?> poll(@RequestParam(value="client_id",required=false) String client,
            @RequestParam(value="device_code",required=false) String high,
            @RequestParam(value="grant_type",required=false) String type,HttpServletRequest req,HttpServletResponse res) {
        limits.apply("device-poll",req,res,120,null,0);
        DeviceService.Poll result;
        try {
            result = devices.poll(client, type, high);
        } catch (DomainException rejected) {
            var reason = rejected.errorCode();
            if (reason != KernelErrorCode.AUTH_FORBIDDEN && reason != KernelErrorCode.AUTH_REVOKED
                    && reason != KernelErrorCode.MEMBERSHIP_EPOCH_INVALID && reason != KernelErrorCode.RESOURCE_NOT_FOUND) {
                throw rejected;
            }
            // The authorization transaction has already rolled back. Persist the terminal denial separately.
            devices.denyRevokedRequest(high);
            result = DeviceService.Poll.failed(KernelErrorCode.DEVICE_ACCESS_DENIED);
        }
        if (result.token() != null) return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.token());
        var code = result.failure();
        String error = switch(code) {
            case DEVICE_AUTH_PENDING -> "authorization_pending";
            case DEVICE_SLOW_DOWN -> "slow_down";
            case DEVICE_EXPIRED -> "expired_token";
            case DEVICE_ACCESS_DENIED -> "access_denied";
            case INVALID_REQUEST -> "invalid_request";
            case AUTH_UNAUTHORIZED -> "invalid_grant";
            default -> throw new IllegalStateException("Unexpected device poll outcome");
        };
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("error",error,"code",code.code(),
                "message",code.defaultMessage(),"requestId",res.getHeader("X-Request-Id"),"details",Map.of()));
    }

    @GetMapping("/credentials")
    List<DeviceService.Grant> credentials(@AuthenticationPrincipal AuthenticatedPrincipal account) {
        return devices.list(account.userId());
    }

    @DeleteMapping("/credentials/{credentialId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal AuthenticatedPrincipal account,@PathVariable String credentialId) {
        devices.revoke(account.userId(),credentialId);
    }
}
