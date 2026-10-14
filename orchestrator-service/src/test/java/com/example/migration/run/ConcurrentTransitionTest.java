package com.example.migration.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:concurrency;MODE=Oracle;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password="
})
class ConcurrentTransitionTest {

  @Autowired
  RunService runs;

  @Test
  void twoCallersRacingTheSameTransitionLeaveExactlyOneWinner() throws Exception {
    long runId = runs.create("race").runId();
    int callers = 6;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    List<Future<HttpStatus>> results = new ArrayList<>();
    for (int i = 0; i < callers; i++) {
      String actor = "caller-" + i;
      results.add(pool.submit(() -> {
        start.await();
        try {
          runs.transition(runId, "PROFILED", actor, null);
          return HttpStatus.OK;
        } catch (RunException e) {
          return e.status();
        }
      }));
    }
    start.countDown();
    int ok = 0;
    int conflicts = 0;
    for (Future<HttpStatus> f : results) {
      HttpStatus s = f.get();
      if (s == HttpStatus.OK) {
        ok++;
      } else if (s == HttpStatus.CONFLICT) {
        conflicts++;
      }
    }
    pool.shutdown();

    assertThat(ok).isEqualTo(1);
    assertThat(conflicts).isEqualTo(callers - 1);
    assertThat(runs.get(runId).state()).isEqualTo(RunState.PROFILED);
  }
}
