package com.example.migration.run;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RunStateTest {

  @Test
  void movesOnlyOneStepForward() {
    assertThat(RunState.PLANNED.canMoveTo(RunState.PROFILED)).isTrue();
    assertThat(RunState.PLANNED.canMoveTo(RunState.READY)).isFalse();
    assertThat(RunState.READY.canMoveTo(RunState.PLANNED)).isFalse();
  }

  @Test
  void rollbackOnlyFromRiskyStages() {
    assertThat(RunState.READY.canMoveTo(RunState.ROLLED_BACK)).isFalse();
    assertThat(RunState.FREEZE.canMoveTo(RunState.ROLLED_BACK)).isTrue();
    assertThat(RunState.MONITOR.canMoveTo(RunState.ROLLED_BACK)).isTrue();
    assertThat(RunState.DONE.canMoveTo(RunState.ROLLED_BACK)).isFalse();
  }

  @Test
  void terminalStatesGoNowhere() {
    for (RunState target : RunState.values()) {
      assertThat(RunState.DONE.canMoveTo(target)).isFalse();
      assertThat(RunState.ROLLED_BACK.canMoveTo(target)).isFalse();
    }
  }
}
