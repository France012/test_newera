package it.enterprise.claims.core.adapter.in.web;

import it.enterprise.claims.core.application.exception.ActorNotFoundException;
import it.enterprise.claims.core.application.exception.ClaimNotFoundException;
import it.enterprise.claims.core.application.exception.DuplicateActorException;
import it.enterprise.claims.core.application.exception.InvalidAssignmentException;
import it.enterprise.claims.core.application.exception.InvalidClaimRequestException;
import it.enterprise.claims.core.application.exception.InvalidClaimStatusTransitionException;
import it.enterprise.claims.core.application.exception.PolicyNotFoundException;
import it.enterprise.claims.core.application.exception.UnknownRoleException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Translates application exceptions into RFC 9457 {@code application/problem+json} responses.
 *
 * <p>This is the single place where HTTP status codes are decided for error paths, which keeps the
 * domain services free of {@code HttpStatus} constants.
 *
 * <p>Ordered at {@link Ordered#HIGHEST_PRECEDENCE} so this advice - not the framework default one -
 * decides the shape of every error response, giving clients one consistent error contract.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /**
   * @param ex raised by the service
   * @param request current request
   * @return 404 problem
   */
  @ExceptionHandler(ClaimNotFoundException.class)
  public ProblemDetail handleClaimNotFound(ClaimNotFoundException ex, HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "Claim not found", ex.getMessage(), request);
  }

  /**
   * @param ex raised by the service
   * @param request current request
   * @return 404 problem
   */
  @ExceptionHandler(PolicyNotFoundException.class)
  public ProblemDetail handlePolicyNotFound(
      PolicyNotFoundException ex, HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "Policy not found", ex.getMessage(), request);
  }

  /**
   * @param ex raised when the asserted {@code actorId} does not exist
   * @param request current request
   * @return 404 problem
   */
  @ExceptionHandler(ActorNotFoundException.class)
  public ProblemDetail handleActorNotFound(ActorNotFoundException ex, HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "Actor not found", ex.getMessage(), request);
  }

  /**
   * @param ex raised when an actor is registered twice with the same subject
   * @param request current request
   * @return 409 problem
   */
  @ExceptionHandler(DuplicateActorException.class)
  public ProblemDetail handleDuplicateActor(
      DuplicateActorException ex, HttpServletRequest request) {
    ProblemDetail detail =
        problem(HttpStatus.CONFLICT, "Actor already registered", ex.getMessage(), request);
    detail.setProperty("subjectRef", ex.getSubjectRef());
    return detail;
  }

  /**
   * @param ex raised when a role code is outside the reference data
   * @param request current request
   * @return 400 problem
   */
  @ExceptionHandler(UnknownRoleException.class)
  public ProblemDetail handleUnknownRole(UnknownRoleException ex, HttpServletRequest request) {
    ProblemDetail detail =
        problem(HttpStatus.BAD_REQUEST, "Unknown role", ex.getMessage(), request);
    detail.setProperty("roleCode", ex.getRoleCode());
    return detail;
  }

  /**
   * @param ex raised when an assignment violates the state of the actor or of the claim
   * @param request current request
   * @return 409 problem
   */
  @ExceptionHandler(InvalidAssignmentException.class)
  public ProblemDetail handleInvalidAssignment(
      InvalidAssignmentException ex, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "Invalid assignment", ex.getMessage(), request);
  }

  /**
   * @param ex raised by the state machine
   * @param request current request
   * @return 409 problem listing the transitions that would have been accepted
   */
  @ExceptionHandler(InvalidClaimStatusTransitionException.class)
  public ProblemDetail handleInvalidTransition(
      InvalidClaimStatusTransitionException ex, HttpServletRequest request) {
    ProblemDetail detail =
        problem(HttpStatus.CONFLICT, "Illegal claim status transition", ex.getMessage(), request);
    detail.setProperty("claimId", ex.getClaimId());
    detail.setProperty("fromStatus", ex.getFromStatus());
    detail.setProperty("requestedStatus", ex.getToStatus());
    detail.setProperty("allowedStatuses", ex.getAllowedTargets());
    return detail;
  }

  /**
   * @param ex raised by the service
   * @param request current request
   * @return 400 problem
   */
  @ExceptionHandler(InvalidClaimRequestException.class)
  public ProblemDetail handleInvalidRequest(
      InvalidClaimRequestException ex, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid claim request", ex.getMessage(), request);
  }

  /**
   * @param ex raised by Bean Validation on the request body
   * @param request current request
   * @return 400 problem with one entry per violating field
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    Map<String, String> errors = new LinkedHashMap<>();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
    ProblemDetail detail =
        problem(
            HttpStatus.BAD_REQUEST,
            "Validation failed",
            "The request body contains invalid fields",
            request);
    detail.setProperty("errors", errors);
    return detail;
  }

  /**
   * @param ex raised by method-level validation
   * @param request current request
   * @return 400 problem
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ProblemDetail handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "Validation failed", ex.getMessage(), request);
  }

  /**
   * @param ex raised when the body cannot be deserialised (malformed JSON, unknown enum value)
   * @param request current request
   * @return 400 problem
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleUnreadableBody(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "Malformed request body",
        "The request body is not valid JSON or contains an unsupported value",
        request);
  }

  /**
   * @param ex raised when a path variable or query parameter has the wrong type
   * @param request current request
   * @return 400 problem
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ProblemDetail handleTypeMismatch(
      MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "Invalid request parameter",
        "Parameter '"
            + ex.getName()
            + "' has an invalid value: "
            + ex.getValue()
            + " (expected "
            + (ex.getRequiredType() == null
                ? "a valid value"
                : ex.getRequiredType().getSimpleName())
            + ")",
        request);
  }

  /**
   * @param ex raised when a mandatory query parameter is missing
   * @param request current request
   * @return 400 problem
   */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ProblemDetail handleMissingParameter(
      MissingServletRequestParameterException ex, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "Missing request parameter",
        "Required parameter '" + ex.getParameterName() + "' is missing",
        request);
  }

  /**
   * Catch-all. Exceptions raised by Spring MVC itself (unsupported method, unsupported media type,
   * ...) implement {@link ErrorResponse}: they are re-emitted with their own status code instead of
   * being flattened into a 500. Anything else is a genuine bug and is logged.
   *
   * @param ex any unhandled exception
   * @param request current request
   * @return the framework problem, or a 500 problem without leaking the stack trace
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
