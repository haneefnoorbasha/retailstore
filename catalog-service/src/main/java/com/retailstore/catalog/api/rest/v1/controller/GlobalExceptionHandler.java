package com.retailstore.catalog.api.rest.v1.controller;

import com.retailstore.catalog.api.rest.v1.dto.response.ApiErrorResponse;
import com.retailstore.catalog.domain.exception.DuplicateProductException;
import com.retailstore.catalog.domain.exception.ProductNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleNotFound(ProductNotFoundException ex, HttpServletRequest request) {
        return ApiErrorResponse.builder()
            .status(404).error("Not Found").message(ex.getMessage())
            .path(request.getRequestURI()).build();
    }

    @ExceptionHandler(DuplicateProductException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleDuplicate(DuplicateProductException ex, HttpServletRequest request) {
        return ApiErrorResponse.builder()
            .status(409).error("Conflict").message(ex.getMessage())
            .path(request.getRequestURI()).build();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        var fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(FieldError::getField, fe -> 
                fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid"));

        return ApiErrorResponse.builder()
            .status(400).error("Validation Failed").message("One or more fields are invalid")
            .path(request.getRequestURI()).fieldErrors(fieldErrors).build();
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiErrorResponse handleGeneral(Exception ex, HttpServletRequest request) {
        log.error("Unhandled error on {}", request.getRequestURI(), ex);
        return ApiErrorResponse.builder()
            .status(500).error("Internal Server Error").message("An unexpected error occurred")
            .path(request.getRequestURI()).build();
    }
}
