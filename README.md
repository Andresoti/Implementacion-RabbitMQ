# Implementacion RabbitMQ

Pedidos360 — DSY1107 / EV2. Angular frontend, Spring Boot microservices and RabbitMQ messaging.

This is an operational README, not an academic report. The EV1 foundation is maintained separately and is not included in this repository.

## Components

- Angular frontend: `pedidos360-front`.
- Spring Boot orders API and producer: `pedidos360-backend/orders-service`.
- Spring Boot domain consumers/results API: `pedidos360-backend/worker-service`.
- Spring Boot RabbitMQ administration API: `pedidos360-backend/rabbit-admin-service`.
- Shared configuration and event contract: `pedidos360-backend/common`.
- PostgreSQL databases and two clustered RabbitMQ nodes: root Compose files.

The three asynchronous effects are an internal notification record (not an email), a ticket identifier and receipt text. They have separate consumers, application queues and DLQs. Orders publish through both topic and direct exchanges. No optional email/cloud service is required.

## Verify without AWS

Requirements: Java 17, Maven, Node/npm, Python 3, OpenSSL and running Docker with Compose.

```sh
(cd pedidos360-backend && ./mvnw clean verify)
(cd pedidos360-front && npm ci --ignore-scripts && npm test && npm run typecheck && npm run build && npm audit)
python -m unittest discover -s scripts -p 'test_*.py' -v
python scripts/check_cluster.py
python scripts/check_local.py
```

The integration checks use randomly named disposable resources and dynamically allocated loopback broker, application and database ports, so an existing development stack does not have to be stopped. Checks initialize actual PostgreSQL schemas, start the three application JARs, verify signed JWT filters, exercise publication/consumption/DLQs/admin APIs, duplicate delivery, bounded transient failures and recovery after a broker outage. They clean up only their own containers and volumes, never an existing application database.

`local_identity.py` is a loopback-only test fixture with ephemeral RSA keys, discovery/JWKS and an Authorization Code/PKCE exchange. It does not create Cognito users, impersonate an AWS deployment, or provide evidence of live IDaaS operation. Production application JWT verification is not bypassed: the test process explicitly supplies the fixture's issuer to its disposable application processes.

### Optional local browser verification

```sh
docker build -t pedidos360-front:ev2-local pedidos360-front
python scripts/check_local.py --serve
```

After the real integration checks pass, open `http://localhost:4200` and use **Sign in**. This explicit test mode uses a synthetic local identity, not your personal account. Create an order and refresh results to inspect all three effects. Ctrl+C stops the test processes and removes only their owned disposable resources. This mode must not be deployed to AWS or described as a Cognito demonstration.

If port 4200 is already in use, pass `--frontend-port 4210` and open the printed local URL. Callback URLs and test CORS are adjusted together for that disposable environment.

## Deployment configuration (real IDaaS)

Copy `.env.example` to an ignored local `.env`; provide the real public Cognito settings and distinct development database/broker passwords. Configure the public JSON in `config/frontend.json` with matching browser callback/logout URLs. It must never contain secrets. Then:

```sh
docker compose config --quiet
docker compose up --build -d --wait
```

This Compose path expects an actual reachable IDaaS, not the test fixture. The frontend is at localhost:4200; APIs are 8080 (orders), 8081 (results), 8082 (admin). Broker management is 15672/15673. Published ports bind only to loopback. In AWS, set browser API bases to API Gateway, configure its routes/CORS/JWT authorizer, and provide the real cloud database connection settings. Do not substitute Docker service names for browser URLs.

Stop normal development resources using `docker compose down`. Do not add `--volumes` unless you intentionally want to delete that environment's database/broker data. Bootstrap SQL runs only for fresh PostgreSQL volumes; existing data is never reset automatically. Hibernate validates the schema.

See each component README for endpoint contracts, scopes and configuration. The ordinary browser user needs `pedidos/read` and `pedidos/write`; administration separately needs `rabbitmq/admin`.

## Verification boundaries

The two-node cluster uses durable classic queues. Membership does not imply replicated queues or broker high availability. Three DLQs retain actual rejected messages; automatic DLQ replay is deliberately not implemented.

Live Cognito registration/login, API Gateway routes and JWT enforcement, ECS/cloud deployment, RDS connectivity and Docker Compose execution in the cloud remain pending access to the professor's laboratory. The previously committed EV1 database credential must still be rotated and historical exposure addressed before publication. No AWS calls, pushes, coursework submission, academic report or presentation are performed by these scripts.
