package com.example.migration.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ProfileServiceTest {

  @Test
  void profilesNullsDuplicatesAndMixedDateFormatsWithoutExposingValues() throws Exception {
    var profile = new ProfileService("src/test/resources/legacy").profile();

    var borrowers = profile.files().stream().filter(f -> f.file().equals("borrowers.csv")).findFirst().orElseThrow();
    assertThat(borrowers.rows()).isEqualTo(3);

    var email = borrowers.columns().stream().filter(c -> c.column().equals("email")).findFirst().orElseThrow();
    assertThat(email.nullCount()).isEqualTo(1);
    assertThat(email.nullPct()).isEqualTo(33.33);

    var dob = borrowers.columns().stream().filter(c -> c.column().equals("dob")).findFirst().orElseThrow();
    assertThat(dob.formats()).isEqualTo(Map.of("ISO yyyy-MM-dd", 1L, "US MM/dd/yyyy", 1L, "dd-MMM-yy", 1L));

    assertThat(profile.toString()).doesNotContain("ana@example.com");
  }
}
