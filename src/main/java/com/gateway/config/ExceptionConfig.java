package com.gateway.config;

import com.gateway.dto.response.GatewayResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class ExceptionConfig {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<GatewayResponse<Void>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(GatewayResponse.error(400, ex.getMessage(), MDC.get("correlationId")));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<GatewayResponse<Void>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(GatewayResponse.error(409, ex.getMessage(), MDC.get("correlationId")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<GatewayResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage).collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(GatewayResponse.error(400, errors, MDC.get("correlationId")));
    }
}
