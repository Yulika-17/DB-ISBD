package com.db.rest;

import com.db.exception.CollectionAlreadyExistsException;
import com.db.exception.CollectionNotFoundException;
import com.db.exception.DatabaseClosedException;
import com.db.exception.DimensionMismatchException;
import com.db.exception.IdNotFoundException;
import com.db.exception.InvalidCollectionNameException;
import com.db.exception.InvalidIdException;
import com.db.exception.StorageException;
import com.db.rest.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler({
            DimensionMismatchException.class,
            InvalidCollectionNameException.class,
            InvalidIdException.class,
            IllegalArgumentException.class,
            HttpMessageNotReadableException.class
    })
    ResponseEntity<ApiError> badRequest(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(this::validationMessage)
                .collect(Collectors.joining(", "));
        return error(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler({CollectionNotFoundException.class, IdNotFoundException.class})
    ResponseEntity<ApiError> notFound(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(CollectionAlreadyExistsException.class)
    ResponseEntity<ApiError> conflict(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(DatabaseClosedException.class)
    ResponseEntity<ApiError> unavailable(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), request);
    }

    @ExceptionHandler({StorageException.class, IOException.class})
    ResponseEntity<ApiError> storage(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage(), request);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message, HttpServletRequest request) {
        ApiError body = new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(body);
    }

    private String validationMessage(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
