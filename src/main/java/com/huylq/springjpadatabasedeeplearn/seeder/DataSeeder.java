package com.huylq.springjpadatabasedeeplearn.seeder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.concurrent.ThreadLocalRandom;
/**
 * Nạp bulk data SAU khi Flyway đã chạy xong (ApplicationRunner chạy khi context
 * đã start hoàn toàn -> schema + lookup data đã sẵn sàng).
 *
 * Điểm cốt lõi của cách này:
 *  1) IDEMPOTENT: nếu bảng users đã có dữ liệu -> bỏ qua. An toàn khi restart app.
 *  2) BATCH JDBC theo từng chunk: gộp nhiều INSERT thành một round-trip.
 *     (Dùng JDBC thuần, KHÔNG qua JPA -> không vướng chuyện IDENTITY chặn batch.)
 *  3) KHÔNG bọc @Transactional -> mỗi chunk tự commit (autocommit). Tránh 1 transaction
 *     khổng lồ ôm 2M dòng (WAL phình, giữ lock lâu). Đổi lại: nếu fail giữa chừng sẽ
 *     có dữ liệu dở dang -> chạy `docker compose down -v` rồi seed lại.
 *
 * Lưu ý hiệu năng: nhớ có ?reWriteBatchedInserts=true trong JDBC URL. pgjdbc sẽ
 * viết lại nhiều câu INSERT trong một batch thành MỘT câu multi-row -> nhanh hơn hẳn.
 */
@Component
@Slf4j
public class DataSeeder implements ApplicationRunner {

  // id range của lookup data do Flyway V2 nạp
  private static final int CATEGORIES = 200;
  private static final int TAGS = 50;

  // volume gốc (sẽ nhân với scale)
  private static final int BASE_USERS        = 50_000;
  private static final int BASE_PRODUCTS     = 20_000;
  private static final int BASE_IMAGES       = 40_000;
  private static final int BASE_PRODUCT_TAGS = 50_000;
  private static final int BASE_ORDERS       = 500_000;
  private static final int BASE_ORDER_ITEMS  = 2_000_000;
  private static final int BASE_PAYMENTS     = 500_000;
  private static final int BASE_REVIEWS      = 300_000;

  private static final String[] PRODUCT_STATUS = {"ACTIVE", "INACTIVE", "OUT_OF_STOCK"};
  private static final String[] ORDER_STATUS   = {"PENDING", "PAID", "SHIPPED", "CANCELLED"};
  private static final String[] PAYMENT_METHOD = {"CARD", "COD", "WALLET"};
  private static final String[] PAYMENT_STATUS = {"INIT", "SUCCESS", "FAILED"};

  private final JdbcTemplate jdbc;
  private final SeedProperties props;

  // số lượng thực tế sau khi áp scale
  private int users, products, images, productTags, orders, orderItems, payments, reviews;

  public DataSeeder(JdbcTemplate jdbc, SeedProperties props) {
    this.jdbc = jdbc;
    this.props = props;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!props.isEnabled()) {
      log.info("Seed is OFF (app.seed.enabled=false).");
      return;
    }

    Long existing = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
    if (existing != null && existing > 0) {
      log.info("Existing data ({} users) -> seed skipped.", existing);
      return;
    }

    double s = props.getScale();
    users       = scale(BASE_USERS, s);
    products    = scale(BASE_PRODUCTS, s);
    images      = scale(BASE_IMAGES, s);
    productTags = scale(BASE_PRODUCT_TAGS, s);
    orders      = scale(BASE_ORDERS, s);
    orderItems  = scale(BASE_ORDER_ITEMS, s);
    payments    = scale(BASE_PAYMENTS, s);
    reviews     = scale(BASE_REVIEWS, s);

    long t0 = System.currentTimeMillis();
    log.info("Started seed (scale={}, batchSize={}). Refer: ~{} order_items.",
        s, props.getBatchSize(), orderItems);

    seedUsers();
    seedAddresses();
    seedProducts();
    seedInventory();
    seedProductImages();
    seedProductTags();
    seedOrders();
    seedOrderItems();
    seedPayments();
    seedReviews();

    log.info("ANALYZE (update for planner)...");
    jdbc.execute("ANALYZE");

    log.info("Seed completed after {} ms.", System.currentTimeMillis() - t0);
  }

  private static int scale(int base, double s) {
    return Math.max(1, (int) Math.round(base * s));
  }

  // ------------------------------------------------------------------
  // Hạ tầng batch: chia total thành các chunk, mỗi chunk một batchUpdate.
  // ------------------------------------------------------------------

  @FunctionalInterface
  private interface RowSetter {
    /** globalIndex chạy 0..total-1 xuyên suốt toàn bảng. */
    void set(PreparedStatement ps, int globalIndex) throws SQLException;
  }

  private void seedInChunks(String label, String sql, int total, RowSetter setter) {
    final int batch = props.getBatchSize();
    final long logEvery = (long) batch * 25;
    int done = 0;
    while (done < total) {
      final int start = done;
      final int size = Math.min(batch, total - done);
      jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
        @Override public int getBatchSize() { return size; }
        @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
          setter.set(ps, start + i);
        }
      });
      done += size;
      if (done == total || done % logEvery == 0) {
        log.info("  {}: {}/{}", label, done, total);
      }
    }
  }

  private static int rndId(int max) { return ThreadLocalRandom.current().nextInt(max) + 1; }

  private static <T> T pick(T[] arr) { return arr[ThreadLocalRandom.current().nextInt(arr.length)]; }

  private static BigDecimal money(double min, double max) {
    return BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(min, max))
        .setScale(2, RoundingMode.HALF_UP);
  }

  private static OffsetDateTime randomLastYear() {
    long secs = ThreadLocalRandom.current().nextLong(0, 365L * 24 * 3600);
    return OffsetDateTime.now().minusSeconds(secs);
  }

  // ------------------------------------------------------------------
  // Các seeder. id của users/products/orders là 1..N vì BIGSERIAL nạp tuần tự.
  // ------------------------------------------------------------------

  private void seedUsers() {
    seedInChunks("users",
        "INSERT INTO users (email, full_name) VALUES (?, ?)",
        users, (ps, i) -> {
          int n = i + 1;
          ps.setString(1, "user" + n + "@test.com");
          ps.setString(2, "User " + n);
        });
  }

  private void seedAddresses() {
    // 1 địa chỉ mặc định / user
    seedInChunks("addresses",
        "INSERT INTO addresses (user_id, line1, city, country, is_default) VALUES (?, ?, ?, ?, true)",
        users, (ps, i) -> {
          int n = i + 1;
          ps.setLong(1, n);
          ps.setString(2, n + " Main St");
          ps.setString(3, "City " + (n % 100));
          ps.setString(4, "VN");
        });
  }

  private void seedProducts() {
    seedInChunks("products",
        "INSERT INTO products (category_id, sku, name, price, status) VALUES (?, ?, ?, ?, ?)",
        products, (ps, i) -> {
          int n = i + 1;
          ps.setLong(1, rndId(CATEGORIES));
          ps.setString(2, "SKU-" + n);
          ps.setString(3, "Product " + n);
          ps.setBigDecimal(4, money(10, 1010));
          ps.setString(5, pick(PRODUCT_STATUS));
        });
  }

  private void seedInventory() {
    // 1 dòng / product
    seedInChunks("inventory",
        "INSERT INTO inventory (product_id, quantity, version) VALUES (?, ?, 0)",
        products, (ps, i) -> {
          ps.setLong(1, i + 1);
          ps.setInt(2, ThreadLocalRandom.current().nextInt(1000));
        });
  }

  private void seedProductImages() {
    seedInChunks("product_images",
        "INSERT INTO product_images (product_id, url, sort_order) VALUES (?, ?, ?)",
        images, (ps, i) -> {
          int n = i + 1;
          ps.setLong(1, rndId(products));
          ps.setString(2, "https://img.example.com/" + n + ".jpg");
          ps.setInt(3, n % 5);
        });
  }

  private void seedProductTags() {
    // ManyToMany: random (product, tag) có thể trùng PK -> ON CONFLICT DO NOTHING
    seedInChunks("product_tags",
        "INSERT INTO product_tags (product_id, tag_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
        productTags, (ps, i) -> {
          ps.setLong(1, rndId(products));
          ps.setLong(2, rndId(TAGS));
        });
  }

  private void seedOrders() {
    seedInChunks("orders",
        "INSERT INTO orders (user_id, status, created_at) VALUES (?, ?, ?)",
        orders, (ps, i) -> {
          ps.setLong(1, rndId(users));
          ps.setString(2, pick(ORDER_STATUS));
          ps.setObject(3, randomLastYear());
        });
  }

  private void seedOrderItems() {
    seedInChunks("order_items",
        "INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (?, ?, ?, ?)",
        orderItems, (ps, i) -> {
          ps.setLong(1, rndId(orders));
          ps.setLong(2, rndId(products));
          ps.setInt(3, ThreadLocalRandom.current().nextInt(5) + 1);
          ps.setBigDecimal(4, money(10, 1010));
        });
  }

  private void seedPayments() {
    seedInChunks("payments",
        "INSERT INTO payments (order_id, amount, method, status) VALUES (?, ?, ?, ?)",
        payments, (ps, i) -> {
          ps.setLong(1, rndId(orders));
          ps.setBigDecimal(2, money(10, 2010));
          ps.setString(3, pick(PAYMENT_METHOD));
          ps.setString(4, pick(PAYMENT_STATUS));
        });
  }

  private void seedReviews() {
    seedInChunks("reviews",
        "INSERT INTO reviews (product_id, user_id, rating, comment, created_at) VALUES (?, ?, ?, ?, ?)",
        reviews, (ps, i) -> {
          ps.setLong(1, rndId(products));
          ps.setLong(2, rndId(users));
          ps.setShort(3, (short) (ThreadLocalRandom.current().nextInt(5) + 1));
          ps.setString(4, "Review comment " + (i + 1));
          ps.setObject(5, randomLastYear());
        });
  }
}