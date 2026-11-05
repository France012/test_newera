package it.enterprise.claims.documents.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.enterprise.claims.documents.application.DocumentService;
import it.enterprise.claims.documents.application.dto.DocumentContent;
import it.enterprise.claims.documents.application.dto.DocumentPageResponse;
import it.enterprise.claims.documents.application.dto.DocumentResponse;
import it.enterprise.claims.documents.application.exception.DocumentNotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/** Web slice of the document controller: multipart upload, streaming, delete and error mapping. */
@WebMvcTest(controllers = DocumentController.class)
class DocumentControllerTest {

  private static final UUID DOCUMENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID CLAIM_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Autowired private MockMvc mockMvc;

  @MockBean private DocumentService documentService;

  @Test
  @DisplayName("POST /api/v1/documents accepts multipart and returns 201 with Location")
  void uploadReturnsCreated() throws Exception {
    when(documentService.upload(any(), eq(CLAIM_ID), eq("INVOICE"))).thenReturn(documentResponse());
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "policy.pdf",
            MediaType.APPLICATION_PDF_VALUE,
            "pdf".getBytes(StandardCharsets.UTF_8));

    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(file)
                .param("claimId", CLAIM_ID.toString())
                .param("documentType", "INVOICE"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost/api/v1/documents/" + DOCUMENT_ID))
        .andExpect(jsonPath("$.fileName").value("policy.pdf"))
        .andExpect(jsonPath("$.claimId").value(CLAIM_ID.toString()));
  }

  @Test
  @DisplayName("GET /api/v1/documents/{id} returns 404 as application/problem+json")
  void getReturnsNotFound() throws Exception {
    when(documentService.findById(DOCUMENT_ID))
        .thenThrow(new DocumentNotFoundException(DOCUMENT_ID));

    mockMvc
        .perform(get("/api/v1/documents/{id}", DOCUMENT_ID))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Document not found"));
  }

  @Test
  @DisplayName("GET /api/v1/documents/{id}/content streams the bytes as an attachment")
  void contentStreamsBytes() throws Exception {
    byte[] bytes = "pdf".getBytes(StandardCharsets.UTF_8);
    when(documentService.download(DOCUMENT_ID))
        .thenReturn(new DocumentContent(documentResponse(), bytes));

    mockMvc
        .perform(get("/api/v1/documents/{id}/content", DOCUMENT_ID))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_PDF))
        // RFC 5987 form, so a non-ASCII file name survives the round trip
        .andExpect(
            header()
                .string(
                    HttpHeaders.CONTENT_DISPOSITION, containsString("filename*=UTF-8''policy.pdf")))
        .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
        .andExpect(content().bytes(bytes));
  }

  @Test
  @DisplayName("GET /api/v1/documents requires the claimId parameter")
  void listRequiresClaimId() throws Exception {
    mockMvc
        .perform(get("/api/v1/documents"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  @DisplayName("GET /api/v1/documents returns the page envelope")
  void listReturnsPage() throws Exception {
    when(documentService.findByClaimId(eq(CLAIM_ID), eq(true), any()))
        .thenReturn(new DocumentPageResponse(List.of(documentResponse()), 0, 20, 1, 1));

    mockMvc
        .perform(get("/api/v1/documents").param("claimId", CLAIM_ID.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(DOCUMENT_ID.toString()))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  @DisplayName("DELETE /api/v1/documents/{id} returns 204")
  void deleteReturnsNoContent() throws Exception {
    mockMvc
        .perform(delete("/api/v1/documents/{id}", DOCUMENT_ID))
        .andExpect(status().isNoContent());
  }

  private static DocumentResponse documentResponse() {
    return new DocumentResponse(
        DOCUMENT_ID,
        DOCUMENT_ID,
        CLAIM_ID,
        "policy.pdf",
        MediaType.APPLICATION_PDF_VALUE,
        3L,
        "INVOICE",
        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
        1,
        true,
        null,
        null,
        false,
        OffsetDateTime.parse("2025-03-10T09:00:00Z"),
        1);
  }
}
