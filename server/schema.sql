-- قاعدة بيانات "خلي بالك" — استوردها من phpMyAdmin
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS parents (
  id          INT AUTO_INCREMENT PRIMARY KEY,
  email       VARCHAR(190) NOT NULL UNIQUE,
  pass_hash   VARCHAR(255) NOT NULL,
  created_at  BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS parent_sessions (
  token_hash  CHAR(64) PRIMARY KEY,
  parent_id   INT NOT NULL,
  created_at  BIGINT NOT NULL,
  FOREIGN KEY (parent_id) REFERENCES parents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS children (
  id               CHAR(16) PRIMARY KEY,
  parent_id        INT NOT NULL,
  token_hash       CHAR(64) NOT NULL UNIQUE,
  name             VARCHAR(100) NOT NULL DEFAULT '',
  model            VARCHAR(100) NOT NULL DEFAULT '',
  last_seen        BIGINT NOT NULL DEFAULT 0,
  battery          INT NOT NULL DEFAULT -1,
  current_app      VARCHAR(255) NOT NULL DEFAULT '',
  live_until       BIGINT NOT NULL DEFAULT 0,
  lock_now_request BIGINT NOT NULL DEFAULT 0,
  alert            VARCHAR(255) NOT NULL DEFAULT '',
  alert_at         BIGINT NOT NULL DEFAULT 0,
  policy           MEDIUMTEXT NOT NULL,
  apps             MEDIUMTEXT NOT NULL,
  version          INT NOT NULL DEFAULT 1,
  created_at       BIGINT NOT NULL,
  INDEX (parent_id),
  FOREIGN KEY (parent_id) REFERENCES parents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pair_codes (
  code        CHAR(8) PRIMARY KEY,
  parent_id   INT NOT NULL,
  child_name  VARCHAR(100) NOT NULL,
  created_at  BIGINT NOT NULL,
  FOREIGN KEY (parent_id) REFERENCES parents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS live_frames (
  child_id  CHAR(16) PRIMARY KEY,
  frame     MEDIUMBLOB NOT NULL,
  ts        BIGINT NOT NULL,
  FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS login_attempts (
  ip          VARCHAR(45) NOT NULL,
  at          BIGINT NOT NULL,
  INDEX (ip, at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
