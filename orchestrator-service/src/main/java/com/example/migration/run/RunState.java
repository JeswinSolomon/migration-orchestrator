package com.example.migration.run;

import java.util.EnumSet;
import java.util.Set;

public enum RunState {
  PLANNED, PROFILED, INITIAL_LOAD, CDC_SYNC, SHADOW_VERIFY, READY, FREEZE, FINAL_SYNC, SWITCHED, MONITOR, DONE, ROLLED_BACK;

  // Until DONE, reverse sync keeps legacy current, so rollback is only safe from the risky stages.
  private static final Set<RunState> ROLLBACK_FROM = EnumSet.of(FREEZE, FINAL_SYNC, SWITCHED, MONITOR);

  public boolean canMoveTo(RunState target) {
    if (target == ROLLED_BACK) {
      return ROLLBACK_FROM.contains(this);
    }
    return this != ROLLED_BACK && this != DONE && target.ordinal() == ordinal() + 1;
  }
}
