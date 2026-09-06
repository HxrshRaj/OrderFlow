package com.orderflow.inventory.web;

import com.orderflow.inventory.domain.IllegalStockAdjustmentException;
import com.orderflow.inventory.service.ConcurrentAdjustmentException;
import com.orderflow.inventory.service.DuplicateSkuException;
import com.orderflow.inventory.service.IllegalReservationStateException;
import com.orderflow.inventory.service.InsufficientStockException;
import com.orderflow.inventory.service.InventoryItemNotFoundException;
import com.orderflow.inventory.service.ReservationNotFoundException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/** Maps domain failures to RFC 7807 {@link ProblemDetail} responses. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail handleInsufficientStock(InsufficientStockException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Insufficient stock");
        pd.setProperty("reservationId", ex.getReservationId());
        pd.setProperty("shortfalls", ex.getShortfalls().stream()
                .map(s -> Map.of(
                        "sku", s.sku(),
                        "requested", s.requested(),
                        "available", s.available(),
                        "reason", s.reason().name()))
                .toList());
        return pd;
    }

    @ExceptionHandler({ReservationNotFoundException.class, InventoryItemNotFoundException.class})
    public ProblemDetail handleNotFound(RuntimeException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Not found");
        return pd;
    }

    @ExceptionHandler(IllegalReservationStateException.class)
    public ProblemDetail handleIllegalState(IllegalReservationStateException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Illegal reservation state transition");
        return pd;
    }

    @ExceptionHandler(DuplicateSkuException.class)
    public ProblemDetail handleDuplicateSku(DuplicateSkuException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Duplicate SKU");
        return pd;
    }

    @ExceptionHandler(ConcurrentAdjustmentException.class)
    public ProblemDetail handleConcurrentAdjustment(ConcurrentAdjustmentException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Concurrent modification");
        return pd;
    }

    @ExceptionHandler(IllegalStockAdjustmentException.class)
    public ProblemDetail handleIllegalAdjustment(IllegalStockAdjustmentException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setTitle("Invalid stock adjustment");
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Bad request");
        return pd;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "request validation failed");
        pd.setTitle("Validation error");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
                .toList();
        pd.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(pd);
    }
}
