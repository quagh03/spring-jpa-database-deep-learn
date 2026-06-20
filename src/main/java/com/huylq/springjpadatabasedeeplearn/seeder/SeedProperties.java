package com.huylq.springjpadatabasedeeplearn.seeder;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình seed, đọc từ application.yml dưới prefix "app.seed".
 *  - enabled:   bật/tắt seed.
 *  - scale:     nhân với volume gốc. Dev nhanh: 0.01 (vài nghìn dòng). Full: 1.0.
 *  - batchSize: số dòng / lần round-trip JDBC. To hơn = ít round-trip = nhanh hơn
 *               (đi cùng reWriteBatchedInserts=true trong JDBC URL).
 */
@ConfigurationProperties(prefix = "app.seed")
@Getter
@Setter
public class SeedProperties {

  private boolean enabled = true;
  private double scale = 1.0;
  private int batchSize = 2000;

}