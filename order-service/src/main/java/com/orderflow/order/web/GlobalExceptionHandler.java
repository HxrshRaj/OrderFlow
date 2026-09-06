package com.orderflow.order.web;

import com.orderflow.order.client.InsufficientStockException;
import com.orderflow.order.client.InventoryUnavailableException;
import com.orderflow.order.domain.IllegalOrderStateException;
import com.orderflow.order.service.OrderNotFoundException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
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

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleNotFound(OrderNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Order not found");
        return pd;
    }

    @ExceptionHandler(IllegalOrderStateException.class)
    public ProblemDetail handleIllegalState(IllegalOrderStateException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Illegal order state transition");
        return pd;
    }

    /**
     * Only reachable from ship/cancel (placement turns a 409 into a REJECTED order body, not
     * an error). Surface it as 409 with the shortfalls.
     */
    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail handleInsufficientStock(InsufficientStockException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.summary());
        pd.setTitle("Insufficient stock");
        pd.setProperty("shortfalls", ex.getShortfalls());
        return pd;
    }

    @ExceptionHandler({InventoryUnavailableException.class, CallNotPermittedException.class})
    public ProblemDetail handleInventoryUnavailable(RuntimeException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "the Inventory Service is currently unavailable; please retry");
        pd.setTitle("Inventory Service unavailable");
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
