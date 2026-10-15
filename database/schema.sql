CREATE DATABASE IF NOT EXISTS btl_chess
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE btl_chess;

CREATE TABLE IF NOT EXISTS users (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(40) NOT NULL UNIQUE,
  password_hash VARCHAR(128) NOT NULL,
  password_salt VARCHAR(64) NOT NULL,
  elo INT NOT NULL DEFAULT 1200,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS matches (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  game_uid VARCHAR(36) NOT NULL UNIQUE,
  white_player_id BIGINT NOT NULL,
  black_player_id BIGINT NOT NULL,
  result VARCHAR(20) NOT NULL,
  reason VARCHAR(120) NOT NULL,
  white_score DECIMAL(2,1) NOT NULL,
  black_score DECIMAL(2,1) NOT NULL,
  move_count INT NOT NULL,
  initial_fen VARCHAR(120) NOT NULL,
  white_elo_before INT NOT NULL,
  white_elo_after INT NOT NULL,
  white_elo_delta INT NOT NULL,
  black_elo_before INT NOT NULL,
  black_elo_after INT NOT NULL,
  black_elo_delta INT NOT NULL,
  started_at TIMESTAMP(3) NOT NULL,
  ended_at TIMESTAMP(3) NOT NULL,
  CONSTRAINT fk_match_white FOREIGN KEY (white_player_id) REFERENCES users(id),
  CONSTRAINT fk_match_black FOREIGN KEY (black_player_id) REFERENCES users(id),
  INDEX idx_match_white_ended (white_player_id, ended_at),
  INDEX idx_match_black_ended (black_player_id, ended_at)
);

CREATE TABLE IF NOT EXISTS moves (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  match_id BIGINT NOT NULL,
  ply INT NOT NULL,
  from_square CHAR(2) NOT NULL,
  to_square CHAR(2) NOT NULL,
  promotion CHAR(1),
  notation VARCHAR(30) NOT NULL,
  fen_after VARCHAR(120) NOT NULL,
  played_at TIMESTAMP(3) NOT NULL,
  CONSTRAINT fk_move_match FOREIGN KEY (match_id) REFERENCES matches(id) ON DELETE CASCADE,
  UNIQUE KEY uq_match_ply (match_id, ply)
);
