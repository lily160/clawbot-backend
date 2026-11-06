CREATE TABLE chat_message (
  id         BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id    VARCHAR(128) NOT NULL,
  role       VARCHAR(32)  NOT NULL,
  content    TEXT         NOT NULL,
  seq        INT          NOT NULL,
  created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_chat_message_user_seq ON chat_message(user_id, seq);
