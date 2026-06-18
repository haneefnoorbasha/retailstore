package com.retailstore.order.api.rest.v1.controller;

import com.retailstore.order.domain.exception.OrderNotCancellableException;
import com.retailstore.order.domain.exception.OrderNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(OrderNotFoundException ex, HttpServletRequest req) {
        return error(404, "Not Found", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(OrderNotCancellableException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleNotCancellable(OrderNotCancellableException ex, HttpServletRequest req) {
        return error(422, "Unprocessable Entity", ex.getMessage(), req.getRequestURI());
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
        private int status;
        private String error;
        private String message;
        private String path;
        private Instant timestamp;
    }
}
