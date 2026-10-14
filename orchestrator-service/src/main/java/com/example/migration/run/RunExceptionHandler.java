package com.example.migration.run;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RunExceptionHandler {

  @ExceptionHandler(RunException.class)
  public ResponseEntity<Map<String, String>> handle(RunException e) {
    return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
  }
}
