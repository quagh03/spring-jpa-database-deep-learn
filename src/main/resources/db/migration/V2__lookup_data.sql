-- =====================================================================
-- V2: LOOKUP DATA (nhỏ & ổn định) -> hợp với Flyway: chạy 1 lần, đi cùng git.
-- Quy tắc phân chia:
--   * Dữ liệu reference nhỏ, ít đổi (categories, tags) -> Flyway migration.
--   * Dữ liệu volume lớn (users/orders/order_items...) -> KHÔNG để ở đây,
--     mà do DataSeeder (ApplicationRunner) nạp bằng batch JDBC.
-- Vì sao? Migration nên nhẹ, deterministic về cấu trúc, và chạy ở MỌI môi
-- trường (kể cả prod). Bạn không muốn nhồi 2M dòng test vào lịch sử migration.
-- =====================================================================

-- 200 categories
INSERT INTO categories (name, slug)
SELECT 'Category ' || g, 'cat-' || g
FROM generate_series(1, 200) g;

-- Dựng cây: category 21..200 nhận parent ngẫu nhiên trong 1..20 (để luyện recursive CTE)
UPDATE categories
SET parent_id = (floor(random() * 20)::int + 1)
WHERE id > 20;

-- 50 tags
INSERT INTO tags (name)
SELECT 'tag-' || g
FROM generate_series(1, 50) g;