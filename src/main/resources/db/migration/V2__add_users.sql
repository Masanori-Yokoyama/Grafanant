CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_users_tenant_username UNIQUE (tenant_id, username)
);

CREATE INDEX idx_users_tenant_id ON users(tenant_id);

-- 初期テストデータ投入 (パスワードはすべて password123)
INSERT INTO users (tenant_id, username, password_hash, display_name, role) VALUES
('tenant-alpha', 'admin', crypt('password123', gen_salt('bf', 10)), 'Alice (Alpha Admin)', 'ADMIN'),
('tenant-alpha', 'user1', crypt('password123', gen_salt('bf', 10)), 'Bob (Alpha Staff)', 'USER'),
('tenant-beta', 'admin', crypt('password123', gen_salt('bf', 10)), 'Carol (Beta Admin)', 'ADMIN'),
('tenant-beta', 'user2', crypt('password123', gen_salt('bf', 10)), 'Dave (Beta Staff)', 'USER'),
('tenant-gamma', 'member', crypt('password123', gen_salt('bf', 10)), 'Eve (Gamma Member)', 'USER');
