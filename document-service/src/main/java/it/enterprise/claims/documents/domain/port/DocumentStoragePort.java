package it.enterprise.claims.documents.domain.port;

import java.util.UUID;

/**
 * Outbound port for the binary content of a document.
 *
 * <p>Phase 1 ships {@code LocalFileSystemStorage}; an S3 adapter only has to implement this
 * interface, which is why the storage key - not a filesystem path - is what the metadata persists.
 */
public interface DocumentStoragePort {

  /**
   * Stores the content and returns the key it can be read back with.
   *
   * @param claimId claim the document belongs to (namespacing)
   * @param documentId identifier of the document
   * @param content raw bytes
   * @return the storage key to persist in the document metadata
   */
  String store(UUID claimId, UUID documentId, byte[] content);

  /**
   * @param storageKey key returned by {@link #store(UUID, UUID, byte[])}
   * @return the raw bytes of the document
   */
  byte[] load(String storageKey);
}
