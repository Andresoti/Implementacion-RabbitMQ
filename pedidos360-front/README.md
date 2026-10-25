# Pedidos360 Angular frontend

Standalone Angular 22 application for authenticated order creation/listing and asynchronous processing results. UI and source are English; the API payload retains the Spanish contract.

## Run locally

Use Node 26.7.0 and npm 11.19.0 (the versions exercised here).

```sh
npm ci --ignore-scripts
cp local-config/config.example.json local-config/config.json
# Set the public Cognito configuration in local-config/config.json.
npm run start:local
```

Open `http://localhost:4200`. `npm start` instead uses the committed public placeholder configuration. The ignored local override is used only by the `local` build/serve configuration and never enters the production Docker build.

```sh
npm test
npm run typecheck
npm run build
npm audit
```

Tests use Angular's supported Vitest/jsdom builder; no browser download is required. `.npmrc` keeps dependency lifecycle scripts disabled. The esbuild postinstall and Parcel native-build install scripts were inspected; the packaged platform binaries worked without enabling either script on the tested Linux host and Alpine Docker build. Keep the lockfile. The scoped Vite 8.3.5 override removes advisories in Angular build's older Vite dependency; rerun build, tests, and audit when updating it.

## Public runtime configuration

Production reads `/assets/config.json` with `cache: no-store` before Angular bootstraps. Mount the parent project's `config/frontend.json` at `/usr/share/nginx/html/assets/config.json:ro` in the container. The configuration is public: never include a client secret, password, token, or AWS credentials.

| Key | Meaning |
| --- | --- |
| `authority` | Cognito user-pool OIDC issuer, not the Hosted UI domain |
| `clientId` | Public app client without a client secret |
| `logoutDomain` | Cognito Hosted UI origin |
| `redirectUri` | Registered frontend callback, e.g. `http://localhost:4200/callback` |
| `logoutRedirectUri` | Registered post-logout URL, e.g. `http://localhost:4200/` |
| `apiBaseUrl` | Browser-reachable order API/gateway base, no `/api/pedidos` suffix |
| `workerApiBaseUrl` | Browser-reachable worker base; omitted/empty defaults to `apiBaseUrl` |
| `scopes` | Space-separated `openid email pedidos/read pedidos/write` |

All URLs must use HTTPS, except HTTP loopback development URLs. Query strings, fragments, and embedded credentials are rejected. Local defaults are API `http://localhost:8080` and worker `http://localhost:8081`. In production both API values normally point to the same Gateway base. Both API servers must allow the frontend origin in CORS; never use Docker service names in browser-facing URLs.

Enable Cognito authorization code flow for the public client, allow the exact callback and logout URLs, and allow the two custom resource-server scopes. The hosted page handles registration if self-service signup is enabled. The frontend does not invent signup parameters or offer a local authentication bypass.

The OIDC library generates/verifies PKCE and transaction state; each sign-in sends a new nonce. Tokens remain in memory, and only temporary OIDC transaction data is kept in sessionStorage. Reloading requires sign-in again. No silent refresh is enabled. Expired sessions and HTTP 401 offer sign-in; HTTP 403 explains missing permission. Bearer tokens are attached only to the exact configured order/results endpoint URLs, with fetch redirects rejected.

## API and processing results

- `GET /api/pedidos`: requires exact `pedidos/read`.
- `POST /api/pedidos`: requires exact `pedidos/write`; sends trimmed `cliente` (1–100 chars), `producto` (1–200 chars), integer `cantidad` (1–10000). Expects HTTP 201 with `estado: CREATED`.
- `GET /api/results`: requires exact `pedidos/read`; renders `eventId`, `orderId`, `domain`, `result`, and `processedAt`.

Orders load on entering the protected `/orders` route. Use **Refresh results** after creating an order; processing is asynchronous, so an empty response can be normal. Results refresh is manual, not background polling. There is no administration UI.

## Container

```sh
docker build -t pedidos360-front .
docker run --rm -p 127.0.0.1:4200:80 \
  -v "$PWD/local-config/config.json:/usr/share/nginx/html/assets/config.json:ro" \
  pedidos360-front
```

The parent Compose project can build context `./pedidos360-front`, publish 4200:80, and mount its public JSON at the same path. Nginx serves callback/order SPA routes with a fallback to index.html, but missing assets/config return 404 rather than HTML. Runtime JSON is never cached. Security headers include CSP, nosniff, frame denial, and no-referrer. Terminate HTTPS and configure HSTS at the production gateway/edge.

Unit/component tests cover configuration, real PKCE/state consumption and nonce rejection, session/logout behavior, route guards, token restrictions, API contracts/errors, and order/results UI. Real Cognito credentials and live backend infrastructure are not part of this subtree's tests; validate the complete hosted login and end-to-end API flow after configuring the parent environment.
