package it.enterprise.claims.core.adapter.in.web;

import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Builds a safe {@link Pageable} from raw request parameters.
 *
 * <p>Deliberately not using the default {@code Pageable} argument resolver: the public contract
 * advertises {@code sort=createdAt,desc}, and a raw property name would either explode inside JPA
 * (unknown property) or leak the persistence model. Here the property is aliased and whitelisted,
 * the page is clamped to {@code >= 0} and the size to {@code 1..100}.
 */
final class PageRequests {

  private static final int DEFAULT_MAX_SIZE = 100;

  private PageRequests() {}

  /**
   * @param page zero-based page index as received from the client
   * @param size requested page size as received from the client
   * @param rawSort raw sort expression, e.g. {@code openedAt,desc}; may be {@code null}
   * @param sortableFields properties the API is allowed to sort on
   * @param aliases API-level property names mapped to persistence-level ones
   * @param fallbackProperty property used when the request is missing or not whitelisted
   * @return a validated page request
   */
  static Pageable of(
      int page,
      int size,
      String rawSort,
      Set<String> sortableFields,
      Map<String, String> aliases,
      String fallbackProperty) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), DEFAULT_MAX_SIZE);
    return PageRequest.of(
        safePage, safeSize, sort(rawSort, sortableFields, aliases, fallbackProperty));
  }

  private static Sort sort(
      String rawSort,
      Set<String> sortableFields,
      Map<String, String> aliases,
      String fallbackProperty) {
    String[] parts = rawSort == null ? new String[0] : rawSort.split(",");
    String requested = parts.length > 0 ? parts[0].trim() : "";
    String property = aliases.getOrDefault(requested, requested);
    if (!sortableFields.contains(property)) {
      property = fallbackProperty;
    }
    Sort.Direction direction =
        parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
            ? Sort.Direction.ASC
            : Sort.Direction.DESC;
    return Sort.by(direction, property);
  }
}
