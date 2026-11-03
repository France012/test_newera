package it.enterprise.claims.documents.adapter.out.storage;

import it.enterprise.claims.documents.application.exception.StorageException;
import it.enterprise.claims.documents.domain.port.DocumentStoragePort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Local filesystem implementation of {@link DocumentStoragePort} (phase 1).
 *
 * <p>The storage key is always derived from generated identifiers ({@code <claimId>/<documentId>}),
 * never from the client-supplied file name, and every resolved path is checked to be inside the
 * configured root: an attacker cannot traverse out of it even by tampering with a persisted key.
 *
 * <p>Root directory: {@code DOCUMENTS_STORAGE_PATH} (default {@code ./target/storage}). Swapping in
 * S3 later means implementing this interface and deleting nothing else.
 */
@Component
public class LocalFileSystemStorage implements DocumentStoragePort {

  private static final Logger LOG = LoggerFactory.getLogger(LocalFileSystemStorage.class);

  private final Path root;

  /**
   * @param rootPath absolute or relative directory where the content is stored
   */
  public LocalFileSystemStorage(
      @Value("${documents.storage.path:./target/storage}") String rootPath) {
    this.root = Paths.get(rootPath).toAbsolutePath().normalize();
    LOG.info("Document content root: {}", root);
  }

  @Override
  public String store(UUID claimId, UUID documentId, byte[] content) {
    String storageKey = claimId + "/" + documentId;
    Path target = resolve(storageKey);
    try {
      Files.createDirectories(target.getParent());
      Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    } catch (IOException ex) {
      throw new StorageException("Unable to store the document content", ex);
    }
    return storageKey;
  }

  @Override
  public byte[] load(String storageKey) {
    Path target = resolve(storageKey);
    if (!Files.isRegularFile(target)) {
      throw new StorageException("Content not found for the requested document");
    }
    try {
      return Files.readAllBytes(target);
    } catch (IOException ex) {
      throw new StorageException("Unable to read the document content", ex);
    }
  }

  /**
   * @param storageKey relative key persisted in the metadata
   * @return the absolute path, guaranteed to be inside the storage root
   */
  private Path resolve(String storageKey) {
    if (storageKey == null || storageKey.isBlank()) {
      throw new StorageException("Storage key must not be blank");
    }
    Path candidate = root.resolve(storageKey).normalize();
    if (!candidate.startsWith(root)) {
      throw new StorageException("Illegal storage key: path traversal attempt rejected");
    }
    return candidate;
  }
}
