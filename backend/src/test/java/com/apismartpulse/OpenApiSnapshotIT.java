package com.apismartpulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Contract discipline (§7.8): the generated OpenAPI document must equal the committed snapshot.
 * After an intentional API change run {@code ./mvnw test -Dtest=OpenApiSnapshotIT
 * -Dopenapi.update=true} and commit backend/openapi/openapi-v1.json in the same change.
 */
class OpenApiSnapshotIT extends IntegrationTest {
  private static final Path SNAPSHOT = Path.of("openapi/openapi-v1.json");

  @Test
  void generatedContractMatchesCommittedSnapshot() throws Exception {
    Res r = get("/v3/api-docs", null);
    assertThat(r.status()).isEqualTo(200);
    // Jackson's pretty printer uses the OS line separator; the snapshot is always LF.
    String generated =
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(r.body()).replace("\r\n", "\n")
            + "\n";
    Files.createDirectories(Path.of("target"));
    Files.writeString(Path.of("target/openapi-generated.json"), generated, StandardCharsets.UTF_8);
    if (Boolean.getBoolean("openapi.update") || !Files.exists(SNAPSHOT)) {
      Files.createDirectories(SNAPSHOT.getParent());
      Files.writeString(SNAPSHOT, generated, StandardCharsets.UTF_8);
    }
    assertThat(generated)
        .isEqualTo(Files.readString(SNAPSHOT, StandardCharsets.UTF_8).replace("\r\n", "\n"));
    assertThat(get("/swagger-ui/index.html", null).status()).isEqualTo(200);
  }
}
