# Backend local operations

Java 17; Maven reactor modules `common`, `orders-service`, `worker-service`, `rabbit-admin-service`.
No AWS deployment or academic report is included.

## Build and run

```sh
./mvnw clean verify
java -jar orders-service/target/orders-service-0.0.1-SNAPSHOT.jar
# In separate terminals, with each service's environment:
java -jar worker-service/target/worker-service-0.0.1-SNAPSHOT.jar
java -jar rabbit-admin-service/target/rabbit-admin-service-0.0.1-SNAPSHOT.jar
# Root Compose can build the same Dockerfile for each module:
docker build --build-arg MODULE=orders-service -t pedidos360-orders .
```

Every service requires `COGNITO_ISSUER`, `COGNITO_CLIENT_ID`, `RABBITMQ_ADDRESSES`
(comma-separated host:port), `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`.
`COGNITO_AUDIENCE` is optional. Access tokens require signature, issuer, expiry,
`token_use=access`, matching `client_id`, and route-specific scopes.
`CORS_ALLOWED_ORIGINS` is a comma-separated explicit allowlist (default localhost:4200).
Set `SERVER_PORT` to 8080 / 8081 / 8082 respectively (default 8080).
Orders and workers additionally require `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.
Use distinct PostgreSQL databases, e.g. `pedidos360_orders` and `pedidos360_worker`.
Initialize them with `database/orders.sql` and `database/worker.sql` respectively.
Hibernate validates rather than modifies production schemas. H2 exists only in tests.
The admin service needs no database. No authentication bypass is present.

## HTTP contracts

`GET /api/public/ping` is the only anonymous application route. All unlisted routes
are denied. Use `Authorization: Bearer <Cognito access token>` for protected routes.
Errors use `{ "error": "invalid_request|unauthorized|forbidden|service_unavailable" }`.

| Service | Method and path | Scope | Success |
| --- | --- | --- | --- |
| Orders | GET /api/pedidos | pedidos/read | 200 array |
| Orders | POST /api/pedidos | pedidos/write | 201 object |
| Workers | GET /api/results | pedidos/read | 200 array |
| Admin | POST /api/rabbitmq/queues | rabbitmq/admin | 201 name |
| Admin | POST /api/rabbitmq/exchanges | rabbitmq/admin | 201 name |
| Admin | POST /api/rabbitmq/bindings | rabbitmq/admin | 201 binding |
| Admin | DELETE /api/rabbitmq/queues/{name} | rabbitmq/admin | 204 |
| Admin | DELETE /api/rabbitmq/exchanges/{name} | rabbitmq/admin | 204 |
| Admin | DELETE /api/rabbitmq/bindings | rabbitmq/admin | 204 |

Order input: `{"cliente":"Ana","producto":"Book","cantidad":2}`.
Customer max 100 characters, product max 200, quantity integer 1..10000. Output:
`{"id":1,"cliente":"Ana","producto":"Book","cantidad":2,"estado":"CREATED"}`.
IDs/status are server-controlled. POST is not HTTP-idempotent: retrying a successful
request creates another order; event-delivery deduplication is separate.

Worker result: `{"eventId":"UUID","orderId":1,"domain":"TICKET","result":"TKT-...","processedAt":"ISO instant"}`.
Domains are `NOTIFICATION`, `TICKET`, `DOCUMENT`. Notification results record an internal
notification, **not an email delivery**. Tickets have generated identifiers; document
results contain generated receipt text. Results are persisted, not synthetic status logs.

Admin queue input: `{"name":"demo.queue","durable":true}`.
Exchange input: `{"name":"demo.exchange","type":"TOPIC","durable":true}` (`DIRECT` also supported).
Binding input for POST and DELETE: `{"exchange":"demo.exchange","queue":"demo.queue","routingKey":"demo.*"}`.
Names allow ASCII letters/digits/dot/underscore/hyphen, max 200, and cannot begin `amq.`.
Queue deletion is conditional on no consumers and no messages. Administrative requests
operate on the shared broker; use disposable resource names, not application resources.
Creating incompatible declarations or unavailable broker operations returns 503 without
broker details. All admin operations require their own scope; ordinary order scopes do not grant them.

## Messaging and recovery

All topology names and routing keys are centralized in `common/src/main/resources/shared.properties`.
`TopologyConfig` declares explicit durable classic queue, exchange, and binding beans
for each domain and DLQ. Applications, not infrastructure definitions, declare topology.
These classic queues are **not replicated HA queues** on a two-node cluster.

Each order and two outbox intents commit in one database transaction. The relay publishes
`order.created` through the topic exchange and `ticket.requested` through the direct exchange.
It uses persistent messages, mandatory returns, and correlated publisher confirms. Intents
are marked published only after an ACK with no return. Unknown outcomes remain pending.
Row locks with `SKIP LOCKED` prevent concurrent relays from claiming the same row. Pending
intents retry with capped exponential delay (5..300 seconds), never silently expire.
`app.outbox.delay-ms` defaults to 1000; each cycle handles up to 10 rows.

Workers consume raw AMQP messages in three domain-specific `@RabbitListener` classes.
They parse bounded JSON explicitly with Jackson 3, ignore Java type headers, validate
canonical UUID, messageId/payload identity, strict numeric types, fields, and timestamps.
A persisted identity fingerprint rejects the same event ID with changed payload, even
across domains. Database uniqueness plus transaction rollback/retry deduplicates concurrent
redelivery. ACK occurs only after database commit; ACK transport failures escape without
attempting a second settlement. Publication/ACK uncertainty can cause duplicate delivery,
not duplicate stored results.

Transient database failures get at most three in-process attempts (100ms, then 200ms delay).
Permanent/malformed events and exhausted transient failures use explicit
`basicNack(tag,false,false)` and the domain DLX route. No infinite requeue or public
failure-injection endpoint exists. `app.worker.max-attempts` (1..10) and
`app.worker.retry-delay-ms` (0..5000) can be adjusted as Spring properties.
Logs identify domains/delivery tags/categories, not payloads, customer names, or tokens.

DLQs retain failures for operator inspection and controlled replay after fixing their cause.
Replay must preserve the payload UUID and AMQP messageId. Never purge a production DLQ
as part of recovery. Completed results and outbox history are intentionally retained;
set a retention policy before non-local use without deleting idempotency evidence while
messages or DLQ replays may still exist.

## Verification boundaries

Maven tests exercise signed JWTs with ephemeral RSA keys and loopback JWKS, API validation,
actual H2 persistence/transactions, outbox rollback/retry, identity races, and manual ACK/NACK
semantics. Mockito broker/channel tests are **not real RabbitMQ integration tests**.
The parent workspace owns the PostgreSQL/RabbitMQ cluster integration and Compose.

Implementation references: Spring AMQP reference sections `message-converters`, `template`,
and `containerAttributes` (docs.spring.io/spring-amqp/reference/amqp/). Jackson 3's converter
is `JacksonJsonMessageConverter`; this implementation deliberately handles raw `Message`
objects instead, so malformed JSON is settled explicitly rather than failing before the listener.
