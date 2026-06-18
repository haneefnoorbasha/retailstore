package com.retailstore.identity.application.service;

import com.retailstore.identity.api.rest.v1.dto.*;
import com.retailstore.identity.domain.exception.*;
import com.retailstore.identity.domain.model.UserAccount;
import com.retailstore.identity.infrastructure.persistence.UserAccountRepository;
import com.retailstore.identity.infrastructure.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IdentityService {

    private final UserAccountRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    @Value("${retail.identity.jwt.expiration-ms:86400000}")
    private long expirationMs;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new UserAlreadyExistsException("email", request.getEmail());
        }
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new UserAlreadyExistsException("username", request.getUsername());
        }

        UserAccount user = UserAccount.builder()
            .id(UUID.randomUUID().toString())
            .email(request.getEmail().toLowerCase().trim())
            .username(request.getUsername().trim())
            .passwordHash(passwordEncoder.encode(request.getPassword()))
            .fullName(request.getFullName().trim())
            .build();

        UserAccount saved = userRepository.save(user);
        log.info("New user registered: id={} email={}", saved.getId(), saved.getEmail());

        String token = jwtTokenProvider.generateToken(saved);
        return buildAuthResponse(saved, token);
    }

    public AuthResponse login(LoginRequest request) {
        UserAccount user = userRepository.findByEmail(request.getEmail().toLowerCase().trim())
            .orElseThrow(InvalidCredentialsException::new);

        if (!user.isActive()) {
            throw new InvalidCredentialsException();
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("Failed login attempt for email={}", request.getEmail());
            throw new InvalidCredentialsException();
        }

        String token = jwtTokenProvider.generateToken(user);
        log.info("User logged in: id={} email={}", user.getId(), user.getEmail());
        return buildAuthResponse(user, token);
    }

    public UserProfileResponse getProfile(String userId) {
        UserAccount user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
        return toProfileResponse(user);
    }

    public AuthResponse refreshToken(String userId) {
        UserAccount user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
        String token = jwtTokenProvider.generateToken(user);
        return buildAuthResponse(user, token);
    }

    private AuthResponse buildAuthResponse(UserAccount user, String token) {
        return AuthResponse.builder()
            .accessToken(token)
            .tokenType("Bearer")
            .expiresIn(expirationMs / 1000)
            .user(toProfileResponse(user))
            .build();
    }

    private UserProfileResponse toProfileResponse(UserAccount u) {
        return UserProfileResponse.builder()
            .id(u.getId()).email(u.getEmail()).username(u.getUsername())
            .fullName(u.getFullName()).role(u.getRole().name())
            .emailVerified(u.isEmailVerified()).createdAt(u.getCreatedAt())
            .build();
    }
}
