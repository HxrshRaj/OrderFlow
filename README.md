# OrderFlow

A small order-and-inventory system built as **two genuinely independent microservices** in
Java 21 / Spring Boot, with a React storefront. It exists to demonstrate one hard problem
done properly: **not overselling stock when orders race for the last unit, across a service
boundary.**

- **Inventory Service** — owns stock levels and issues concurrency-safe *reservations* (holds). Its own PostgreSQL database.
- **Order Service** — owns the order lifecycle (`PLACED → CONFIRMED → SHIPPED`, plus `REJECTED` / `CANCELLED`) and coordinates with Inventory over REST. Its own, separate PostgreSQL database.
- **web** — a React SPA served by nginx, which also reverse-proxies `/api/*` to the two services so the browser sees a single origin.

```
                       ┌────────────────────────┐
   browser  ──────────▶│  web  (nginx + React)  │
   :8088               └───────┬────────────────┘
                        /api/orders   /api/inventory
                               │            │
                     ┌─────────▼──────┐  ┌──▼───────────────┐
                     │ Order Service  │  │ Inventory Service│
                     │   :8080        │──▶│   :8081          │   REST: reserve / commit / release
                     └──────┬─────────┘  └──────┬───────────┘
                            │                   │
                     ┌──────▼──────┐     ┌──────▼──────┐
                     │  order_db   │     │ inventory_db│      one PostgreSQL per service
                     └─────────────┘     └─────────────┘
```

There is **no shared database** and no cross-database foreign key. The Order Service stores a
`reservation_id` (a UUID it generates) as its only link to Inventory; everything else crosses
the wire as JSON.

---

## Why this shape, and not others

| Decision | Choice | Reason |
|---|---|---|
| Split or monolith? | Two services, two databases | The interesting problem (concurrent reservation across a boundary, partial failure, retries) only exists if the boundary is real. A shared DB would erase it. |
| REST or a message broker? | Synchronous REST + a reservation/hold pattern | Placing an order genuinely *is* a request/response question ("can I have this stock — yes or no?"). A broker in the middle of an RPC is just RPC with more moving parts. A broker would earn its place for fan-out events (`OrderShipped` → notifications, analytics) — services this project doesn't have. |
| No Spring Cloud (Eureka / Config Server / Gateway) | Deliberately omitted | Two services and one compose file don't have the problems those solve. A plain nginx reverse proxy gives the single-origin frontend without the ceremony. |
| Auth | Not implemented | `customerId` is passed in the request body. JWT would slot in at the gateway; it isn't what this project is about. |

---

## The concurrency problem (the point of the project)

**The race:** `available_quantity = 1`. Two reservation requests arrive within microseconds.
Both read `1`, both check `1 >= 1`, both write `0`. Two units sold, one in the warehouse.

OrderFlow prevents this with **three layers**, all in the Inventory Service — the single place
where stock is enforced:

### 1. Atomic conditional decrement (the hot path)

Holding stock is **one statement**, never a read-then-write in Java:

```sql
UPDATE inventory_item
   SET available_quantity = available_quantity - :qty,
       reserved_quantity  = reserved_quantity  + :qty
 WHERE sku = :sku
   AND available_quantity >= :qty      -- the guard
```

PostgreSQL takes a row lock for the duration of that statement, so two requests racing for
the same SKU are **serialised on the row**. The `available_quantity >= :qty` guard is
therefore always evaluated against a committed value. The first request wins (1 row updated);
the second sees the updated value and is rejected (0 rows updated). No lost update, no
negative stock. This is `InventoryItemRepository.reserve(...)`; the decision logic that reads
its row-count is in `ReservationService.reserve(...)`.

*Why this over pessimistic `SELECT … FOR UPDATE`?* Same correctness, but a single atomic
statement holds its lock for microseconds instead of for the length of a transaction, has no
deadlock surface for single-SKU holds, and degrades better under a last-unit stampede — which
is exactly the contention profile that matters here.

### 2. `@Version` optimistic locking (the cold path)

Admin restock / correction (`PATCH /api/v1/inventory/{sku}`) *does* do a genuine
read-modify-write on the JPA entity, so it carries a `@Version` column. A concurrent change
throws `ObjectOptimisticLockingFailureException`; `InventoryService.adjustStock` retries the
whole transaction up to 3× with jittered backoff before giving up with `409`.

### 3. A hard database floor

`CHECK (available_quantity >= 0)` and `CHECK (reserved_quantity >= 0)` in the schema. Even a
logic bug cannot persist negative stock — the database refuses the write.

### Multi-line orders are all-or-nothing

A reservation with three SKUs holds all three or none. `ReservationService.reserve` runs in
one transaction, processes lines **in SKU order** (a stable lock-acquisition order, so two
concurrent multi-line reservations can't deadlock), and if any line can't be held it throws —
rolling back every line that already succeeded *and* the reservation row itself. A rejected
reservation leaves no trace, which makes retrying one naturally safe.

### Idempotency

The Order Service generates a `reservationId` per order and reuses it on every retry. The
Inventory Service inserts that id (unique constraint) **before** touching stock, so two
requests with the same id are serialised on the index: the second either sees the committed
winner and replays its result, or — if the winner rolled back — proceeds on its own. A
retried reserve can therefore never double-decrement. `POST /api/v1/reservations` returns
`201` for a fresh hold and `200` (`replayed: true`) for a replay.

### Holds don't leak

A `@Scheduled` sweeper in the Inventory Service releases any `CONFIRMED` reservation older
than its TTL (default 15 min). If the Order Service crashes between reserving and
committing, the stock comes back on its own.

### Proof

`inventory-service` `ReservationConcurrencyIT` (real PostgreSQL via Testcontainers):

- 40 threads race for `available_quantity = 1` → **exactly 1** succeeds, 39 get `409`, the row ends at `available = 0, reserved = 1`, never negative.
- 50 threads, `available = 10` → **exactly 10** succeed.
- 20 threads submit the **same** `reservationId` concurrently → stock moves **once**.
- A multi-line reservation with one short line → the whole thing rolls back; the line that could have been held is untouched.

---

## Inter-service failure handling (Order Service)

The order is persisted as `PLACED` in a short transaction. The reservation call to Inventory
then happens **with no transaction and no database connection held** (`OrderService` uses a
`TransactionTemplate` around each DB step, not an `@Transactional` method wrapping the network
call). The result is applied in a second short transaction.

The HTTP call is wrapped in **Resilience4j**:

- **Timeouts** on the client itself — 500 ms connect, 2 s read. A hung Inventory Service can't hang an order.
- **Retry** — 3 attempts, exponential backoff + jitter, **only** for `InventoryUnavailableException` (timeout / connection refused / 5xx). A `409` is a final answer and is never retried.
- **Circuit breaker** — sustained failures open it; further calls fail fast instead of piling on.

What the caller sees:

| Inventory result | Order becomes | `POST /orders` returns |
|---|---|---|
| Reserved | `CONFIRMED` | `201`, `status: CONFIRMED` |
| `409` insufficient | `REJECTED` (+ `rejectionReason`) | `201`, `status: REJECTED` |
| Timeout / 5xx / circuit open, after retries | stays `PLACED` | `201`, `status: PLACED` |

A `PLACED` order isn't lost: `PendingOrderReconciler` re-attempts the reservation on a timer
(reusing the same `reservationId`, so it's safe) and moves the order to `CONFIRMED` or
`REJECTED`. `order-service` `OrderFlowIT` covers all three paths, including "Inventory down →
order stays PLACED → Inventory recovers → reconciler confirms it".

---

## Running it

### Everything, in containers

```bash
docker compose up --build
```

Then:

| URL | What |
|---|---|
| http://localhost:8088 | the storefront |
| http://localhost:8081/swagger-ui.html | Inventory Service API |
| http://localhost:8080/swagger-ui.html | Order Service API |

Compose starts five containers — `inventory-db`, `order-db`, `inventory-service`,
`order-service`, `web` — with health checks, so `order-service` only starts once
`inventory-service` reports healthy. Databases keep their data in named volumes; wipe with
`docker compose down -v`.

**Try the race yourself:** open the storefront in two tabs and order the last unit of
"Collector Edition" from both at once. One order is `CONFIRMED`, the other `REJECTED`.

### Services locally (without Docker)

Each service needs a PostgreSQL. With the compose databases running (`docker compose up
inventory-db order-db`):

```bash
cd inventory-service && ./mvnw spring-boot:run     # :8081
cd order-service     && ./mvnw spring-boot:run     # :8080  (needs Inventory up)
cd frontend          && npm install && npm run dev  # :5173, proxies /api/* to :8081 / :8080
```

### On Render

[`render.yaml`](render.yaml) is a Blueprint that provisions the whole system: two managed
PostgreSQL instances (one per service), the two Spring services as Docker web services, and
the nginx `web` service. In the Render dashboard: **New → Blueprint → point at this repo**.

The apps are PaaS-portable without code changes:

- both services bind `$PORT` (`server.port: ${PORT:...}`);
- the datasource URL falls back to being assembled from `*_DB_HOST` / `*_DB_PORT` / `*_DB_NAME` (what a managed provider gives you), so `render.yaml` wires those with `fromDatabase`;
- the gateway's upstreams and the Order Service's inventory URL come from env (`PROXY_SCHEME`, `*_UPSTREAM_HOST`, `INVENTORY_SCHEME`/`INVENTORY_HOST`), wired with `fromService` — no hostnames are hard-coded.

Caveats: free Postgres instances expire after ~30 days and free web services cold-start
(~50 s); the in-container Maven build may need `starter`-tier build resources. Bump the
`plan:` fields for anything long-lived.

---

## Tests

```bash
cd inventory-service && ./mvnw verify      # unit (Surefire) + integration (Failsafe)
cd order-service     && ./mvnw verify
```

- `./mvnw test` runs the fast unit tests only (JUnit 5 + Mockito).
- `./mvnw verify` also runs `*IT` integration tests. These need **Docker** — they start a
  real `postgres:16-alpine` via Testcontainers (H2 is avoided on purpose: it doesn't
  reproduce PostgreSQL's row-locking). The Order Service ITs additionally stub the Inventory
  Service with WireMock.
- If Testcontainers can't reach your Docker daemon, point the ITs at a PostgreSQL you started
  yourself: `./mvnw verify -Dit.postgres.url=jdbc:postgresql://localhost:5432/inventory_db`.

Coverage of the failure modes (not just happy paths):

| Test | Proves |
|---|---|
| `ReservationConcurrencyIT` | no oversell under 40–50 concurrent reservations; concurrent same-id decrements once; multi-line rollback |
| `ReservationLifecycleIT` | reserve→commit / reserve→release maths; idempotent commit; expiry sweep returns stock |
| `ReservationServiceTest`, `InventoryServiceTest` | shortfall vs unknown-SKU, duplicate-key → replay, optimistic-lock retry then give up |
| `InventoryClientTest` | `409` → `InsufficientStockException` with parsed shortfalls; `500` and read-timeout → `InventoryUnavailableException`; `release` 404 → no-op |
| `OrderServiceTest` | place → confirmed / rejected / stays-placed; ship & cancel state guards; reconciler |
| `OrderFlowIT` | end-to-end place/reject/ship/cancel over HTTP; **Inventory down → PLACED → recovers → reconciled** |

---

## UI tests and defect tracking

Two additions on top of the system above: a real browser test suite, and real defect
tickets filed against a real issue tracker. Neither changes the backend or frontend design;
the frontend gained a handful of `data-testid` attributes purely as test hooks.

### Selenium UI suite (`selenium-tests/`)

A standalone Maven module (JUnit 5 + Selenium WebDriver 4 + WebDriverManager, consistent
with the rest of the repo's Java/JUnit stack) that drives real, headless Chrome against the
actual running storefront — no mocked browser, no stubbed backend. It covers three real
user flows:

| Test | Flow |
|---|---|
| `PlaceOrderUiTest` | Set a quantity on an in-stock catalogue item, place the order, confirm it renders as `CONFIRMED`. |
| `OrderRejectionUiTest` | Request far more units than exist, confirm the order renders as `REJECTED` with the real shortfall reason on the order card. |
| `OrderStatusUiTest` | Place an order, ship it from the UI, confirm the badge flips to `SHIPPED` — then independently re-fetches that order straight from the Order Service's own API (bypassing the browser) and asserts the two agree. |

Run it against a running stack:

```bash
docker compose up -d --build          # or point at a deployed environment, see below
cd selenium-tests
./mvnw test
```

Configuration (system property or env var, either works):

| Property | Env var | Default | Meaning |
|---|---|---|---|
| `orderflow.baseUrl` | `ORDERFLOW_BASE_URL` | `http://localhost:8088` | the storefront under test |
| `orderflow.orderServiceUrl` | `ORDER_SERVICE_URL` | `http://localhost:8080` | Order Service API, used only for the independent check in `OrderStatusUiTest` |
| `selenium.headless` | `SELENIUM_HEADLESS` | `true` | set `false` to watch the browser locally |

```bash
./mvnw test -Dorderflow.baseUrl=https://your-deployed-app.example.com \
            -Dorderflow.orderServiceUrl=https://your-order-service.example.com
```

Every wait is an explicit `WebDriverWait` condition (element clickable, order count reaches
N, status reaches one of a set) — there is no `Thread.sleep` anywhere in the suite, and each
test uses a freshly generated `customerId` so runs never interfere with each other or with
data left over from a previous run. Run three times back to back locally: 3/3 green,
identical results.

**A real bug this suite found:** while automating the rejection flow, the "order rejected"
banner turned out to be unreadable in practice — `App.jsx`'s `placeOrder()` sets it, then
immediately calls `refresh()`, whose success path unconditionally clears it
(`setError(null)`), and the same clear fires again on every 3-second poll after that. The
order's rejection reason is still correct and still shown on the order card (driven by a
different, non-racy code path), so `OrderRejectionUiTest` asserts on that instead of pinning
a wait on a banner that cannot reliably appear — see the Javadoc on that test for the full
account. This is filed as a real ticket, not silently patched, since fixing frontend
behaviour is outside this addition's scope:
**[KAN-2](https://hraj15709.atlassian.net/browse/KAN-2)**.

### JIRA defect tracking (`jira-integration/`)

A small Python script, `jira-integration/create_defect_tickets.py`, that files real tickets
in a real Atlassian Cloud (JIRA) project over the JIRA Cloud REST API v3 — no mocking, and
it re-fetches each ticket after creating it to prove it actually exists rather than trusting
the create call's response.

Setup:

```bash
cd jira-integration
pip install -r requirements.txt
cp .env.example .env       # fill in real values — never commit this file
python create_defect_tickets.py --dry-run    # prints the exact payloads, no network calls
python create_defect_tickets.py              # creates + verifies both tickets for real
```

| Env var | Meaning |
|---|---|
| `JIRA_SITE_URL` | your Atlassian Cloud site, e.g. `https://your-domain.atlassian.net` |
| `JIRA_EMAIL` | the Atlassian account the API token belongs to |
| `JIRA_API_TOKEN` | generate at id.atlassian.com/manage-profile/security/api-tokens — **never commit this** |
| `JIRA_PROJECT_KEY` | the target project's key |
| `JIRA_ISSUE_TYPE` | optional, defaults to `Bug`; the script probes the project's real issue types and falls back to `Task` if `Bug` isn't one of them |

It files two tickets, both real and verified — created via the API, then independently
re-fetched with a separate GET to confirm they genuinely exist rather than trusting the
create response:

1. **[KAN-1](https://hraj15709.atlassian.net/browse/KAN-1)** — the historical oversell
   defect: the concurrent-reservation race described above under "The concurrency problem",
   written up as a proper bug report (title, description, repro steps) even though it's
   already fixed, with a direct reference to `ReservationConcurrencyIT` as the regression
   test that proves the fix holds.
2. **[KAN-2](https://hraj15709.atlassian.net/browse/KAN-2)** — the rejection-banner race
   found while building this suite (see above), with repro steps, the exact file/root cause,
   and a reference to `OrderRejectionUiTest` as the coverage that documents it.

(Both tickets live in a private Jira Cloud instance — the links will prompt for login if you
don't have access; the ticket keys and this README are the durable record.)

Credentials are read from the environment only (via `python-dotenv` locally); `.env` is
gitignored repo-wide and `.env.example` ships with placeholders only.

---

## Layout

```
orderflow/
├─ inventory-service/     Spring Boot · owns inventory_db · the stock-enforcement service
│  └─ src/main/java/com/orderflow/inventory/
│     ├─ domain/          InventoryItem (@Version), Reservation, ReservationLine
│     ├─ repository/      the atomic conditional-UPDATE lives here
│     ├─ service/         ReservationService (reserve/commit/release/sweep), InventoryService
│     ├─ web/             controllers + RFC 7807 handler
│     └─ scheduler/       expired-hold sweeper
├─ order-service/         Spring Boot · owns order_db · order lifecycle + Inventory coordination
│  └─ src/main/java/com/orderflow/order/
│     ├─ domain/          Order (state machine), OrderItem
│     ├─ client/          InventoryClient — Resilience4j retry + circuit breaker
│     ├─ service/         OrderService — network calls outside transactions
│     ├─ web/             controllers + RFC 7807 handler
│     └─ scheduler/       stuck-order reconciler
├─ frontend/              React + Vite SPA; built and served by nginx, which proxies /api/*
├─ selenium-tests/        standalone Maven module: real headless-Chrome UI regression suite
├─ jira-integration/      Python script filing real defect tickets via the JIRA Cloud REST API
├─ docs/API.md            endpoint reference + example payloads
└─ docker-compose.yml     5 services, health-gated startup, one DB per service
```

## Tech

Java 21 · Spring Boot 3.4 · Spring Data JPA · PostgreSQL 16 · Flyway · Resilience4j ·
Apache HttpClient 5 · springdoc-openapi · JUnit 5 · Mockito · Testcontainers · WireMock ·
React 18 · Vite · nginx · Docker Compose · Render Blueprint · Selenium WebDriver ·
WebDriverManager · JIRA Cloud REST API.
