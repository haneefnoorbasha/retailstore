package com.retailstore.identity.api.rest.v1.controller;

import com.retailstore.identity.domain.exception.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(UserNotFoundException ex, HttpServletRequest req) {
        return error(404, "Not Found", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleConflict(UserAlreadyExistsException ex, HttpServletRequest req) {
        return error(409, "Conflict", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleUnauthorized(InvalidCredentialsException ex, HttpServletRequest req) {
        return error(401, "Unauthorized", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneral(Exception ex, HttpServletRequest req) {
        return error(500, "Internal Server Error", "An unexpected error occurred", req.getRequestURI());
    }

    private ErrorResponse error(int status, String error, String message, String path) {
        return ErrorResponse.builder().status(status).error(error)
            .message(message).path(path).timestamp(Instant.now()).build();
    }

    @Getter @Builder
    public static class ErrorResponse {
        private int status; private String error;
        private String message; private String path; private Instant timestamp;
    }
}
