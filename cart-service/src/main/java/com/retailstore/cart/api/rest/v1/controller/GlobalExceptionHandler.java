package com.retailstore.cart.api.rest.v1.controller;

import com.retailstore.cart.domain.exception.CartItemNotFoundException;
import com.retailstore.cart.domain.exception.InvalidQuantityException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CartItemNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(CartItemNotFoundException ex, HttpServletRequest req) {
        return error(404, "Not Found", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(InvalidQuantityException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalidQty(InvalidQuantityException ex, HttpServletRequest req) {
        return error(400, "Bad Request", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneral(Exception ex, HttpServletRequest req) {
        return error(500, "Internal Server Error", "An unexpected error occurred", req.getRequestURI());
    }

    private ErrorResponse error(int status, String error, String message, String path) {
        return ErrorResponse.builder().status(status).error(error).message(message)
            .path(path).timestamp(Instant.now()).build();
    }

    @Getter @Builder
    public static class ErrorResponse {
        private int status; private String error; private String message;
        private String path; private Instant timestamp;
    }
}
