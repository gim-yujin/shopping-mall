#!/bin/bash
echo "🔄 데이터베이스 초기화 중..."

psql -U postgres -d shopping_mall_db << SQL
TRUNCATE TABLE user_tier_history, search_logs, product_inventory_history, 
             reviews, user_coupons, coupons, wishlists, carts, 
             order_items, orders, product_images, products, 
             categories, users, user_tiers CASCADE;

ALTER SEQUENCE user_tiers_tier_id_seq RESTART WITH 1;
ALTER SEQUENCE users_user_id_seq RESTART WITH 1;
ALTER SEQUENCE categories_category_id_seq RESTART WITH 1;
ALTER SEQUENCE products_product_id_seq RESTART WITH 1;
ALTER SEQUENCE product_images_image_id_seq RESTART WITH 1;
ALTER SEQUENCE orders_order_id_seq RESTART WITH 1;
ALTER SEQUENCE order_items_order_item_id_seq RESTART WITH 1;
ALTER SEQUENCE carts_cart_id_seq RESTART WITH 1;
ALTER SEQUENCE wishlists_wishlist_id_seq RESTART WITH 1;
ALTER SEQUENCE coupons_coupon_id_seq RESTART WITH 1;
ALTER SEQUENCE user_coupons_user_coupon_id_seq RESTART WITH 1;
ALTER SEQUENCE reviews_review_id_seq RESTART WITH 1;
ALTER SEQUENCE product_inventory_history_history_id_seq RESTART WITH 1;
ALTER SEQUENCE search_logs_log_id_seq RESTART WITH 1;
ALTER SEQUENCE user_tier_history_history_id_seq RESTART WITH 1;
SQL

echo "✅ 초기화 완료!"
