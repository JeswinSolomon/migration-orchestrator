package com.example.migration.load;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Pre-migration data profiling (FR1). Reports counts only, never raw values, so it is PII-free. */
@Service
public class ProfileService {

  public record ColumnProfile(String column, long nullCount, double nullPct, long duplicateValues,
                              Map<String, Long> formats) {
  }

  public record FileProfile(String file, long rows, List<ColumnProfile> columns) {
  }

  public record Profile(List<FileProfile> files) {
  }

  private static final Pattern DATE_COLUMN = Pattern.compile(".*(date|dob).*", Pattern.CASE_INSENSITIVE);
  private static final Pattern ISO = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
  private static final Pattern US = Pattern.compile("\\d{1,2}/\\d{1,2}/\\d{4}");
  private static final Pattern DMY = Pattern.compile("\\d{1,2}-[A-Za-z]{3}-\\d{2,4}");

  private final Path legacyDir;

  public ProfileService(@Value("${migration.legacy-dir}") String legacyDir) {
    this.legacyDir = Paths.get(legacyDir);
  }

  public Profile profile() throws IOException {
    List<FileProfile> files = new ArrayList<>();
    try (var stream = Files.list(legacyDir)) {
      for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".csv")).sorted().toList()) {
        files.add(profileFile(file));
      }
    }
    return new Profile(files);
  }

  private FileProfile profileFile(Path file) throws IOException {
    CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1);
         CSVParser parser = format.parse(reader)) {
      List<String> headers = parser.getHeaderNames();
      Map<String, Long> nulls = new HashMap<>();
      Map<String, Long> dups = new HashMap<>();
      Map<String, Set<String>> seen = new HashMap<>();
      Map<String, Map<String, Long>> formats = new HashMap<>();
      long rows = 0;
      for (CSVRecord record : parser) {
        rows++;
        for (String column : headers) {
          String value = record.isSet(column) ? record.get(column).trim() : "";
          if (value.isEmpty()) {
            nulls.merge(column, 1L, Long::sum);
            continue;
          }
          if (!seen.computeIfAbsent(column, k -> new HashSet<>()).add(value)) {
            dups.merge(column, 1L, Long::sum);
          }
          if (DATE_COLUMN.matcher(column).matches()) {
            formats.computeIfAbsent(column, k -> new LinkedHashMap<>()).merge(dateFormat(value), 1L, Long::sum);
          }
        }
      }
      List<ColumnProfile> columns = new ArrayList<>();
      for (String column : headers) {
        long nullCount = nulls.getOrDefault(column, 0L);
        double pct = rows == 0 ? 0 : Math.round(nullCount * 10000.0 / rows) / 100.0;
        columns.add(new ColumnProfile(column, nullCount, pct, dups.getOrDefault(column, 0L),
          formats.getOrDefault(column, Map.of())));
      }
      return new FileProfile(file.getFileName().toString(), rows, columns);
    }
  }

  private static String dateFormat(String value) {
    if (ISO.matcher(value).matches()) {
      return "ISO yyyy-MM-dd";
    }
    if (US.matcher(value).matches()) {
      return "US MM/dd/yyyy";
    }
    return DMY.matcher(value).matches() ? "dd-MMM-yy" : "other";
  }
}
