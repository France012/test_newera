package it.enterprise.claims.documents.adapter.in.web;

import it.enterprise.claims.documents.application.exception.DocumentNotFoundException;
import it.enterprise.claims.documents.application.exception.DocumentOnLegalHoldException;
import it.enterprise.claims.documents.application.exception.DocumentVersionConflictException;
import it.enterprise.claims.documents.application.exception.InvalidDocumentException;
import it.enterprise.claims.documents.application.exception.RetentionViolationException;
import it.enterprise.claims.documents.application.exception.StorageException;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Translates application exceptions into RFC 9457 {@code application/problem+json} responses.
 *
 * <p>Ordered at {@link Ordered#HIGHEST_PRECEDENCE} so this advice, not the framework default one,
 * decides the shape of every error response of the service.
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
  @ExceptionHandler(DocumentNotFoundException.class)
  public ProblemDetail handleNotFound(DocumentNotFoundException ex, HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "Document not found", ex.getMessage(), request);
  }

  /**
   * @param ex raised by the service
   * @param request current request
   * @return 400 problem
   */
  @ExceptionHandler(InvalidDocumentException.class)
  public ProblemDetail handleInvalid(InvalidDocumentException ex, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid document request", ex.getMessage(), request);
  }

  /**
   * @param ex raised when the content store fails
   * @param request current request
   * @return 500 problem whose detail does not leak the storage key
   */
  @ExceptionHandler(StorageException.class)
  public ProblemDetail handleStorage(StorageException ex, HttpServletRequest request) {
    LOG.error("Storage failure on {} {}", request.getMethod(), request.getRequestURI(), ex);
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Document storage unavailable",
        "The document content could not be accessed. Please retry later.",
        request);
  }

  /**
   * @param ex raised when the {@code file} part is missing
   * @param request current request
   * @return 400 problem
   */
  @ExceptionHandler(MissingServletRequestPartException.class)
  public ProblemDetail handleMissingPart(
      MissingServletRequestPartException ex, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "Missing request part",
        "Required multipart part '" + ex.getRequestPartName() + "' is missing",
        request);
  }

  /**
   * @param ex raised when the upload exceeds {@code spring.servlet.multipart.max-file-size}
   * @param request current request
   * @return 413 problem
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ProblemDetail handleTooLarge(
      MaxUploadSizeExceededException ex, HttpServletRequest request) {
    return problem(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "Upload too large",
        "The uploaded file exceeds the maximum allowed size",
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
        "Parameter '" + ex.getName() + "' has an invalid value: " + ex.getValue(),
        request);
  }

  /**
   * @param ex raised when the retention date is not acceptable
   * @param request current request
   * @return 400 problem
   */
  @ExceptionHandler(RetentionViolationException.class)
  public ProblemDetail handleRetention(
      RetentionViolationException ex, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid retention policy", ex.getMessage(), request);
  }

  /**
   * @param ex raised when the document is on legal hold
   * @param request current request
   * @return 409 problem
   */
  @ExceptionHandler(DocumentOnLegalHoldException.class)
  public ProblemDetail handleLegalHold(
      DocumentOnLegalHoldException ex, HttpServletRequest request) {
    ProblemDetail detail =
        problem(HttpStatus.CONFLICT, "Document on legal hold", ex.getMessage(), request);
    detail.setProperty("documentId", ex.getDocumentId());
    return detail;
  }

  /**
   * @param ex raised when two callers create a version of the same document concurrently
   * @param request current request
   * @return 409 problem
   */
  @ExceptionHandler(DocumentVersionConflictException.class)
  public ProblemDetail handleVersionConflict(
      DocumentVersionConflictException ex, HttpServletRequest request) {
    ProblemDetail detail =
        problem(HttpStatus.CONFLICT, "Document version conflict", ex.getMessage(), request);
    detail.setProperty("documentKey", ex.getDocumentKey());
    return detail;
  }

  /**
   * Catch-all. Exceptions raised by Spring MVC itself (missing parameter, unsupported media type,
   * unsupported method, ...) implement {@link ErrorResponse}: they keep their own status code
   * instead of being flattened into a 500. Anything else is a genuine bug and is logged.
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
