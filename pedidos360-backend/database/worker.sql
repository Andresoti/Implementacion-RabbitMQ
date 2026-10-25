CREATE TABLE event_identity (
 event_id VARCHAR(36) PRIMARY KEY,
 fingerprint VARCHAR(64) NOT NULL
);
CREATE TABLE processing_result (
 event_id VARCHAR(36) NOT NULL REFERENCES event_identity(event_id),
 domain VARCHAR(20) NOT NULL CHECK(domain IN ('NOTIFICATION','TICKET','DOCUMENT')),
 order_id BIGINT NOT NULL CHECK(order_id>0),
 result TEXT NOT NULL,
 processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(event_id,domain)
);
