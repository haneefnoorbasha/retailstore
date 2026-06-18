package com.retailstore.identity.api.rest.v1.controller;

import com.retailstore.identity.api.rest.v1.dto.*;
import com.retailstore.identity.application.service.IdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identity")
@RequiredArgsConstructor
@Tag(name = "Identity", description = "Registration, login, JWT tokens, user profiles")
public class IdentityController {

    private final IdentityService identityService;

    @PostMapping("/register")
    @Operation(summary = "Register a new customer account")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(identityService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Login with email and password — returns JWT")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(identityService.login(request));
    }

    @GetMapping("/profile/{userId}")
    @Operation(summary = "Get user profile by ID")
    public ResponseEntity<UserProfileResponse> getProfile(@PathVariable String userId) {
        return ResponseEntity.ok(identityService.getProfile(userId));
    }

    @PostMapping("/refresh/{userId}")
    @Operation(summary = "Refresh JWT token for a user")
    public ResponseEntity<AuthResponse> refresh(@PathVariable String userId) {
        return ResponseEntity.ok(identityService.refreshToken(userId));
    }
}
