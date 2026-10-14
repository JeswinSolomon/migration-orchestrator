package com.example.migration.load;

import org.springframework.boot.test.context.SpringBootTest;

/** Same expectations as the default batch mode, so the one-row-at-a-time fallback stays correct. */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:loadtestrow;MODE=Oracle;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "migration.load.batch-insert=false"
})
class BulkLoaderRowModeTest extends BulkLoaderTest {
}
