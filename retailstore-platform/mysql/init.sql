-- RetailStore dev MySQL init
-- Runs once on first container start; creates all databases and users

CREATE DATABASE IF NOT EXISTS catalogdb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS ordersdb  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS keycloakdb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'catalog_user'@'%'  IDENTIFIED BY 'catalog_pass';
CREATE USER IF NOT EXISTS 'orders_user'@'%'   IDENTIFIED BY 'orders_pass';
CREATE USER IF NOT EXISTS 'keycloak_user'@'%' IDENTIFIED BY 'keycloak_pass';

GRANT ALL PRIVILEGES ON catalogdb.*  TO 'catalog_user'@'%';
GRANT ALL PRIVILEGES ON ordersdb.*   TO 'orders_user'@'%';
GRANT ALL PRIVILEGES ON keycloakdb.* TO 'keycloak_user'@'%';

FLUSH PRIVILEGES;
