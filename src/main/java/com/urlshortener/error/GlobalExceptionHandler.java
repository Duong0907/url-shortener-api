package com.urlshortener.error;

import java.net.URI;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// Every error response is a ProblemDetail with a stable `type` from ErrorType.
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        ProblemDetail problem = problem(ex.getErrorType(), ex.getMessage());

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = problem(ErrorType.INTERNAL_ERROR, null);

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
        MethodArgumentNotValidException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        ProblemDetail problem = problem(
            ErrorType.VALIDATION_FAILED,
            "One or more fields are invalid"
        );
        List<Map<String, String>> errors = ex
            .getBindingResult()
            .getFieldErrors()
            .stream()
            .map(error ->
                Map.of(
                    "field",
                    error.getField(),
                    "message",
                    String.valueOf(error.getDefaultMessage())
                )
            )
            .toList();
        problem.setProperty("errors", errors);

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private static ProblemDetail problem(ErrorType errorType, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorType.getStatus(), detail);
        problem.setType(URI.create(errorType.getType()));
        problem.setTitle(errorType.getTitle());

        return problem;
    }
}
