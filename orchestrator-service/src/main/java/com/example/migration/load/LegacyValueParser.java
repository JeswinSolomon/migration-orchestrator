package com.example.migration.load;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Cleansing rules from docs/02-cleansing-plan.md. Pure functions so they are easy to unit test. */
public final class LegacyValueParser {

  private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
    DateTimeFormatter.ISO_LOCAL_DATE,
    DateTimeFormatter.ofPattern("MM/dd/uuuu"),
    // yy 50-99 -> 19xx, 00-49 -> 20xx
    new DateTimeFormatterBuilder()
      .parseCaseInsensitive()
      .appendPattern("dd-MMM-")
      .appendValueReduced(ChronoField.YEAR, 2, 2, 1950)
      .toFormatter(Locale.ENGLISH)
      .withChronology(IsoChronology.INSTANCE));

  private static final Map<String, String> STATUS = Map.of(
    "A", "ACTIVE", "ACTIVE", "ACTIVE",
    "C", "CLOSED", "CLOSED", "CLOSED",
    "D", "DEFAULT", "DEFAULT", "DEFAULT");

  private LegacyValueParser() {
  }

  /** Blank means unknown and returns null; a non-blank value in no known format is an error. */
  public static LocalDate parseDate(String raw) {
    if (isBlank(raw)) {
      return null;
    }
    String value = raw.trim();
    for (DateTimeFormatter format : DATE_FORMATS) {
      try {
        return LocalDate.parse(value, format);
      } catch (DateTimeParseException ignored) {
        // try the next format
      }
    }
    throw new IllegalArgumentException("Unrecognized date: " + raw);
  }

  public static BigDecimal parseDecimal(String raw) {
    if (isBlank(raw)) {
      return null;
    }
    try {
      return new BigDecimal(raw.trim().replace("%", ""));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid number: " + raw);
    }
  }

  public static String mapStatus(String raw) {
    String mapped = raw == null ? null : STATUS.get(raw.trim().toUpperCase(Locale.ROOT));
    if (mapped == null) {
      throw new IllegalArgumentException("Unknown status: " + raw);
    }
    return mapped;
  }

  public static String titleCase(String raw) {
    StringBuilder out = new StringBuilder();
    for (String word : raw.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
      if (out.length() > 0) {
        out.append(' ');
      }
      out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
    }
    return out.toString();
  }

  public static String digitsOnly(String raw) {
    if (isBlank(raw)) {
      return null;
    }
    String digits = raw.replaceAll("\\D", "");
    return digits.isEmpty() ? null : digits;
  }

  public static String blankToNull(String raw) {
    return isBlank(raw) ? null : raw.trim();
  }

  private static boolean isBlank(String raw) {
    return raw == null || raw.isBlank();
  }
}
