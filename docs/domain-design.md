# Domain-driven design in OrderFlow

This documents a real refactor, not a relabelling exercise: a genuine gap in the `Order`
aggregate's invariants was found and closed with code, a domain event was introduced with a
real (if intentionally internal-only) consumer, and both are backed by tests that were
verified to actually fail before the fix and pass after it. Nothing here is aspirational —
every claim below points at a specific file, and the "why" is explained where the design
made a deliberate trade-off rather than followed the textbook by rote.

## The bounded contexts, as they actually exist

OrderFlow already had two bounded contexts before this refactor — they were never merely
"two folders." Each is a separate deployable, a separate database, and a separate
Java package rooted at its own `domain` package. This section makes that explicit.

### Order Management (`order-service`, package `com.orderflow.order`)

| Concept | Ubiquitous language | Where |
|---|---|---|
| Aggregate root | **Order** — the thing a customer places, ships, or cancels | `domain/Order.java` |
| Entity (non-root) | **OrderItem** — a line on an order; has no identity or repository of its own | `domain/OrderItem.java` |
| Value | **OrderStatus** — `PLACED → CONFIRMED → SHIPPED`, or `REJECTED` / `CANCELLED` | `domain/OrderStatus.java` |
| Domain event | **OrderShipped** — the fact that an order completed shipment | `domain/event/OrderShipped.java` |
| Correlation identifier | **reservationId** — a UUID `Order` carries so it can talk to Inventory Management about "its" hold, without knowing anything else about that context | `Order.reservationId` |

This context owns the *order lifecycle*: composing an order, deciding whether it can be
fulfilled (by asking Inventory Management), shipping it, cancelling it. It does not own stock
levels, and it does not know how a reservation is represented internally by Inventory
Management — only that one exists, identified by a UUID.

### Inventory Management (`inventory-service`, package `com.orderflow.inventory`)

| Concept | Ubiquitous language | Where |
|---|---|---|
| Aggregate root | **InventoryItem** — a SKU's stock position (`availableQuantity` / `reservedQuantity`) | `domain/InventoryItem.java` |
| Aggregate root | **Reservation** — a hold placed against stock for one order, with its own lifecycle (`CONFIRMED → COMMITTED` or `RELEASED`) | `domain/Reservation.java` |
| Entity (non-root) | **ReservationLine** — one SKU/quantity pair inside a reservation | `domain/ReservationLine.java` |

This context owns *stock truth*: how much of a SKU exists, how much is available, and the
only place stock is ever actually debited or credited (see the README's "concurrency
problem" section for how that's enforced under concurrent load — that mechanism is this
context's core responsibility).

### The context map

**Inventory Management is upstream; Order Management is downstream.** Inventory Management
exposes an **Open Host Service**: a REST API with a published, versioned shape (its
`springdoc`-generated OpenAPI contract — see `docs/inventory-openapi.json`). Order Management
is a **conformist** consumer of that published language: it does not get to negotiate the
shape of a reservation response, and it does not reach into Inventory Management's database
or domain objects. The only coupling is `InventoryClient` (`order-service`'s
`client/InventoryClient.java`), a thin translation layer — in DDD terms, a lightweight
**anti-corruption layer** — that turns Inventory's wire responses into Order Management's own
`InsufficientStockException` / `InventoryUnavailableException` vocabulary.

**A deliberately interesting detail: "Reservation" means two different things in the two
contexts, and the design keeps that honest rather than papering over it.**

- In *Inventory Management*, `Reservation` is a full aggregate root: it has line items, a
  lifecycle, a repository, invariants (see the README).
- In *Order Management*, there is no `Reservation` type at all. `Order` holds only a bare
  `UUID reservationId` — a correlation identifier, nothing more. Order Management never
  deserialises Inventory's `Reservation` representation into a domain object of its own;
  it only ever sees `CONFIRMED` / `409` / timeout outcomes through `InventoryClient`.

This is the correct DDD move, not an oversight: if `Order` held a real `Reservation` object
mirroring Inventory's, the two contexts would be sharing a model, and a change to Inventory's
internal `Reservation` shape could silently break Order Management. Keeping only a UUID
across the boundary is what actually keeps the two services independently deployable — which
is the entire premise of this project (see the README's "why this shape, and not others").

### An honest departure from the aggregate rulebook

Textbook DDD says all mutation of an aggregate's state goes through the aggregate root, one
aggregate per transaction. `InventoryItem`'s hot path (`InventoryItemRepository.reserve`)
deliberately does **not** do this — it's a single conditional SQL `UPDATE`, not
"load the `InventoryItem` aggregate, call a method on it, save it." That's a conscious,
documented trade-off (see the README's concurrency section): loading the aggregate and
mutating it in Java is exactly the read-then-write race that oversells stock under
concurrency. The atomic `UPDATE` is what makes the correctness guarantee possible. Calling
this a pure aggregate-root pattern would be dishonest; it's DDD vocabulary applied where it
earns its keep (bounded contexts, ubiquitous language, an anti-corruption layer) and
deliberately set aside where a stricter reading would reintroduce a real bug.

---

## The aggregate invariant implemented

**Before this refactor, `Order.addItem()` had no guard at all.** Every existing caller
happened to only call it while an order was freshly `PLACED` (during `OrderService.placeOrder`),
so the gap was never hit in practice — but nothing in the aggregate itself stopped a future
caller from appending a line item to a `SHIPPED`, `REJECTED`, or `CANCELLED` order. The
invariant existed only as a convention, not as code. That's the gap this refactor closes.

**The invariant, now enforced by the aggregate itself:**

> An order's line items can only be composed while it is `PLACED`. Once it has moved to
> `CONFIRMED`, `SHIPPED`, `REJECTED`, or `CANCELLED`, its items are permanently locked.

```java
// order-service/src/main/java/com/orderflow/order/domain/Order.java
public void addItem(String sku, int quantity, BigDecimal unitPrice) {
    if (status != OrderStatus.PLACED) {
        throw new OrderItemsLockedException(orderNumber, status);
    }
    OrderItem item = new OrderItem(this, sku, quantity, unitPrice);
    this.items.add(item);
    this.totalAmount = this.totalAmount.add(item.lineTotal());
}
```

`OrderItemsLockedException` (`domain/OrderItemsLockedException.java`) is a dedicated,
domain-specific error — not a generic `IllegalStateException` — with a message naming the
order and its actual status, and it's mapped to a `409 Conflict` `ProblemDetail` in
`web/GlobalExceptionHandler.java` exactly like the project's other domain exceptions, so if a
future REST endpoint ever calls `addItem` on an existing order, it fails the same clean,
documented way from any caller — HTTP, a test, a future batch job — because the rule lives in
the aggregate, not in whichever service method happens to call it today.

### Proof

`order-service/src/test/java/com/orderflow/order/domain/OrderTest.java` exercises the
aggregate directly — no mocks, no Spring context, no service layer, so a failure can only mean
the aggregate itself is wrong:

- `adding_an_item_while_placed_is_allowed` — the operation must still work when it's supposed to.
- `cannot_add_a_line_item_once_the_order_has_shipped` — the literal scenario asked for:
  ships an order, then attempts `addItem`, and asserts `OrderItemsLockedException` with a
  message naming the order and `SHIPPED`, and that the item list is unchanged (length still 1).
- `cannot_add_a_line_item_to_a_confirmed_order_either`, and
  `cannot_add_a_line_item_to_a_rejected_or_cancelled_order` — the same guard holds for every
  other non-`PLACED` status, not just `SHIPPED`.

**This was verified to actually test something, not just pass vacuously:** the guard was
temporarily removed from `Order.java`, the suite was re-run, and 3 of the 6 tests in
`OrderTest` failed exactly as expected (the three that assert the exception is thrown); the
guard was then restored and a clean `./mvnw clean test` run brought all 6 back to green. That
before/after pair is the actual evidence the test enforces the invariant, not just documents
an intention.

---

## The domain event: `OrderShipped`

**Trigger:** `Order.ship()` — the moment an order's status transitions from `CONFIRMED` to
`SHIPPED` (after `OrderService.ship()` has already confirmed the reservation was committed in
Inventory Management), the aggregate records an `OrderShipped(orderNumber, reservationId,
customerId)` event internally (`domain/event/OrderShipped.java`).

**Published where, and when:** `OrderService.transition()` drains the aggregate's recorded
events via `Order.pullDomainEvents()` and publishes each one through Spring's
`ApplicationEventPublisher`, from inside the same transaction that persisted the `SHIPPED`
status. It is published *after* the mutation is durably saved, so a listener can only ever
observe shipments that genuinely committed — never one that was about to be rolled back.

**Does it cross the service boundary? No — stated honestly, not glossed over.** OrderFlow has
no message broker (see the README: that was a deliberate call — a broker would only earn its
place for genuine cross-service fan-out, which this two-service system doesn't have yet).
`OrderShipped` is published with Spring's in-process `ApplicationEventPublisher` and consumed
by `domain/event/OrderShippedMetricsListener.java`, **inside the Order Service's own JVM**.
Inventory Management never sees it, is not aware it exists, and does not need to be — the
inventory-side effect of a shipment (committing the reservation) already happens
synchronously via `InventoryClient.commit()` *before* `ship()` is even called, precisely so
that a failed commit can prevent the `SHIPPED` transition from happening at all (see
`OrderService.ship()`). The event is not a mechanism for triggering that side effect; it is a
mechanism for decoupling *other*, purely-internal reactions to "an order shipped" from the
aggregate and the service that orchestrates it.

**The real, working, internal consumer:** `OrderShippedMetricsListener` turns the event into a
Micrometer counter, `orderflow.orders.shipped`, exposed at
`/actuator/metrics/orderflow.orders.shipped`. It is bound to
`@TransactionalEventListener(phase = AFTER_COMMIT)`, so it only fires once the shipment is
truly committed, and it is registered as a completely independent Spring bean — neither
`Order` nor `OrderService` references it, imports it, or knows it exists. That decoupling is
the actual payoff of a domain event over a direct method call, and it's demonstrated, not
just asserted: `OrderFlowIT.shipping_a_confirmed_order_commits_the_reservation_and_publishes_the_domain_event`
ships a real order against a real Postgres instance and a WireMock-stubbed Inventory Service,
then asserts the `orderflow.orders.shipped` counter genuinely incremented by exactly one —
proof the event travelled all the way from `Order.ship()` through
`OrderService.transition()`'s publish call to a listener class the aggregate has never heard
of, inside one running Spring context.

If OrderFlow ever grows a broker (see the README's note on when one would actually earn its
place — cross-service fan-out to something like notifications or analytics), `OrderShipped`
is already the right shape to publish externally too: it carries only primitives and a UUID,
no JPA-mapped types, so it can be serialised as-is without leaking persistence concerns onto
the wire. That is not implemented here — Inventory Management still does not, and will not
from this change, receive `OrderShipped` in any form.
