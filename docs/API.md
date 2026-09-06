# OrderFlow API reference

Two services, each with its own database, each documented live with Swagger:

| Service | Base URL (compose) | Swagger UI | OpenAPI JSON |
|---|---|---|---|
| Inventory Service | `http://localhost:8081` | `/swagger-ui.html` | `/v3/api-docs` &nbsp;(snapshot: [`inventory-openapi.json`](inventory-openapi.json)) |
| Order Service | `http://localhost:8080` | `/swagger-ui.html` | `/v3/api-docs` &nbsp;(snapshot: [`order-openapi.json`](order-openapi.json)) |

Through the gateway (`http://localhost:8088`) the browser reaches:

- `GET  /api/inventory`, `PATCH /api/inventory/{sku}`
- `GET  /api/orders`, `POST /api/orders`, `POST /api/orders/{orderNumber}/ship`, `POST /api/orders/{orderNumber}/cancel`

---

## Inventory Service

### `GET /api/v1/inventory`
List every stock item.

```json
[
  { "sku": "SKU-KEYBOARD", "name": "Mechanical Keyboard",
    "availableQuantity": 25, "reservedQuantity": 0, "onHandQuantity": 25,
    "updatedAt": "2026-09-06T10:15:00Z" }
]
```

### `GET /api/v1/inventory/{sku}`
One item, or `404` with an RFC 7807 body.

### `POST /api/v1/inventory`
Create an item.

```json
{ "sku": "SKU-DESK", "name": "Standing Desk", "quantity": 10 }
```
`201 Created`, `Location: /api/v1/inventory/SKU-DESK`.

### `PATCH /api/v1/inventory/{sku}`
Restock or correct on-hand stock. Optimistic-locked with bounded retry.

```json
{ "quantityDelta": 5 }
```
`400` if `quantityDelta` is `0`; `422` if the change would drive available stock below zero.

### `POST /api/v1/reservations`  — the concurrency-critical endpoint
Hold stock for an order. **Idempotent** on `reservationId`. **Atomic** across all lines.

```json
{
  "reservationId": "0f8d...c21",
  "lines": [ { "sku": "SKU-KEYBOARD", "quantity": 2 },
             { "sku": "SKU-MOUSE",    "quantity": 1 } ]
}
```

| Response | Meaning |
|---|---|
| `201 Created` | Stock held. Body: `{ reservationId, status: "CONFIRMED", lines, expiresAt, replayed: false }` |
| `200 OK` | Replay of an already-known `reservationId`. Same body, `replayed: true` |
| `409 Conflict` | Rejected. Nothing held. RFC 7807 body with a `shortfalls` array: `[{ sku, requested, available, reason }]` where `reason` is `INSUFFICIENT_STOCK` or `UNKNOWN_SKU` |

### `POST /api/v1/reservations/{reservationId}/commit`
Convert a hold into a physical deduction (order shipped). Idempotent. `404` if unknown, `409` if the reservation was already released.

### `POST /api/v1/reservations/{reservationId}/release`
Return a hold to available stock (order cancelled / failed). Idempotent. `409` if already committed.

### `GET /api/v1/reservations/{reservationId}`
Current reservation state.

---

## Order Service

### `POST /api/v1/orders`
Place an order. Persists the order, then reserves stock in the Inventory Service.

```json
{
  "customerId": "demo-customer",
  "lines": [ { "sku": "SKU-KEYBOARD", "quantity": 2, "unitPrice": 79.00 } ]
}
```

Always `201 Created` with the order body. **Inspect `status`:**

| `status` | Meaning |
|---|---|
| `CONFIRMED` | Stock reserved. |
| `REJECTED` | Inventory said no. `rejectionReason` explains which SKUs fell short. |
| `PLACED` | Inventory was unreachable / slow / circuit open. The reconciler will retry; poll the order. |

```json
{
  "orderNumber": "ORD-1000",
  "reservationId": "0f8d...c21",
  "customerId": "demo-customer",
  "status": "CONFIRMED",
  "rejectionReason": null,
  "totalAmount": 158.00,
  "items": [ { "sku": "SKU-KEYBOARD", "quantity": 2, "unitPrice": 79.00, "lineTotal": 158.00 } ],
  "createdAt": "2026-09-06T10:20:00Z",
  "updatedAt": "2026-09-06T10:20:01Z"
}
```

### `GET /api/v1/orders/{orderNumber}`
One order, or `404`.

### `GET /api/v1/orders?customerId={id}`
A customer's orders, newest first.

### `POST /api/v1/orders/{orderNumber}/ship`
`CONFIRMED → SHIPPED`. Commits the reservation in the Inventory Service.
`409` if the order is not `CONFIRMED`; `503` if the Inventory Service cannot be reached.

### `POST /api/v1/orders/{orderNumber}/cancel`
`PLACED | CONFIRMED → CANCELLED`. Releases any stock hold (safe even if none exists).
`409` from any other state; `503` if the Inventory Service cannot be reached.

---

## Error shape (both services)

All errors are [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807) `application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Insufficient stock",
  "status": 409,
  "detail": "reservation 0f8d...c21 rejected: 1 line(s) could not be held",
  "reservationId": "0f8d...c21",
  "shortfalls": [ { "sku": "SKU-LASTUNIT", "requested": 1, "available": 0, "reason": "INSUFFICIENT_STOCK" } ]
}
```
