package com.example.migration.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LegacyValueParserTest {

  @Test
  void parsesAllThreeDateFormats() {
    assertThat(LegacyValueParser.parseDate("2021-03-05")).isEqualTo(LocalDate.of(2021, 3, 5));
    assertThat(LegacyValueParser.parseDate("03/05/2021")).isEqualTo(LocalDate.of(2021, 3, 5));
    assertThat(LegacyValueParser.parseDate("05-Mar-21")).isEqualTo(LocalDate.of(2021, 3, 5));
  }

  @Test
  void twoDigitYearPivotsAt1950() {
    assertThat(LegacyValueParser.parseDate("05-Jan-72")).isEqualTo(LocalDate.of(1972, 1, 5));
    assertThat(LegacyValueParser.parseDate("05-Jan-49")).isEqualTo(LocalDate.of(2049, 1, 5));
  }

  @Test
  void blankDateIsNullButGarbageIsRejected() {
    assertThat(LegacyValueParser.parseDate("")).isNull();
    assertThatThrownBy(() -> LegacyValueParser.parseDate("yesterday")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void stripsPercentAndMapsStatus() {
    assertThat(LegacyValueParser.parseDecimal("5.1%")).isEqualByComparingTo("5.1");
    assertThat(LegacyValueParser.mapStatus("D")).isEqualTo("DEFAULT");
    assertThat(LegacyValueParser.mapStatus("ACTIVE")).isEqualTo("ACTIVE");
    assertThatThrownBy(() -> LegacyValueParser.mapStatus("X")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void normalizesNamesAndPhones() {
    assertThat(LegacyValueParser.titleCase("ANA SMITH")).isEqualTo("Ana Smith");
    assertThat(LegacyValueParser.digitsOnly("(555) 0101")).isEqualTo("5550101");
    assertThat(LegacyValueParser.digitsOnly("")).isNull();
  }
}
