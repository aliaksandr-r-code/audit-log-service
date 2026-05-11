package alaiksandr_r.auditlogservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SortDirectionTest {

  @Test
  void hasExactlyTwoDirections() {
    assertThat(SortDirection.values()).containsExactly(SortDirection.ASC, SortDirection.DESC);
  }
}
