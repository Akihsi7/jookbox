package com.dev.jookbox.web.rest;

import com.dev.jookbox.security.AuthenticatedMember;
import com.dev.jookbox.service.PermissionService;
import com.dev.jookbox.web.dto.PermissionUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/rooms/{code}/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @PostMapping("/{membershipId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Set<String>> updateCapabilities(@PathVariable String code,
                                                       @PathVariable UUID membershipId,
                                                       @AuthenticationPrincipal AuthenticatedMember member,
                                                       @Valid @RequestBody PermissionUpdateRequest request) {
        Set<String> updated = permissionService.updateCapabilities(code, membershipId, request, member);
        return Map.of("capabilities", updated);
    }
}
