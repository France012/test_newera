package it.enterprise.claims.bff.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Error mapping of the BFF.
 *
 * <p>A failure of a downstream service must never be reported to the browser as a failure of the
 * BFF itself: a downstream 404 stays a 404 (the resource genuinely does not exist), any other
 * downstream error becomes 502 Bad Gateway, and a connection-level failure becomes 503 Service
 * Unavailable. The downstream body is never relayed, so internal details cannot leak through the
 * aggregation layer.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /**
   * @param ex error response received from a downstream service
   * @param request current request
   * @return 404 when the downstream said not-found, 502 otherwise
   */
  @ExceptionHandler(RestClientResponseException.class)
  public ResponseEntity<ProblemDetail> handleDownstream(
      RestClientResponseException ex, HttpServletRequest request) {
    if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(
              problem(
                  HttpStatus.NOT_FOUND,
                  "Resource not found",
                  "The requested resource does not exist",
                  request));
    }
    LOG.warn(
        "Downstream error {} on {} {}",
        ex.getStatusCode(),
        request.getMethod(),
        request.getRequestURI());
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(
            problem(
                HttpStatus.BAD_GATEWAY,
                "Downstream service error",
                "A service the BFF depends on returned an error. Please retry later.",
                request));
  }

  /**
   * @param ex connection-level failure (DNS, refused connection, timeout)
   * @param request current request
   * @return 503 problem
   */
  @ExceptionHandler(ResourceAccessException.class)
  public ResponseEntity<ProblemDetail> handleUnavailable(
      ResourceAccessException ex, HttpServletRequest request) {
    LOG.error("Downstream unreachable on {} {}", request.getMethod(), request.getRequestURI(), ex);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Downstream service unavailable",
                "A service the BFF depends on is unreachable. Please retry later.",
                request));
  }

  /**
   * Catch-all. Framework MVC exceptions implement {@link ErrorResponse} and keep their status; real
   * bugs become a 500 without leaking the stack trace.
   *
   * @param ex any unhandled exception
   * @param request current request
   * @return the framework problem, or a 500 problem
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
    if (ex instanceof ErrorResponse errorResponse) {
      ProblemDetail body = errorResponse.getBody();
      body.setInstance(URI.create(request.getRequestURI()));
      return ResponseEntity.status(errorResponse.getStatusCode()).body(body);
    }
    LOG.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "The request could not be completed. Please retry later or contact support.",
                request));
  }

  private static ProblemDetail problem(
      HttpStatus status, String title, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    problem.setInstance(URI.create(request.getRequestURI()));
    return problem;
  }
}
