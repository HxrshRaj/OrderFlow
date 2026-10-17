import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from './api.js';

// The Inventory Service does not own pricing, so the storefront keeps a small price list.
const PRICES = {
  'SKU-KEYBOARD': 79.0,
  'SKU-MOUSE': 25.0,
  'SKU-MONITOR': 329.0,
  'SKU-DOCK': 149.0,
  'SKU-WEBCAM': 59.0,
  'SKU-HEADSET': 199.0,
  'SKU-CABLE': 9.0,
  'SKU-LASTUNIT': 999.0,
};
const priceOf = (sku) => PRICES[sku] ?? 19.99;
const money = (n) => `$${Number(n).toFixed(2)}`;

const STATUS_CLASS = {
  PLACED: 'badge badge-placed',
  CONFIRMED: 'badge badge-confirmed',
  SHIPPED: 'badge badge-shipped',
  REJECTED: 'badge badge-rejected',
  CANCELLED: 'badge badge-cancelled',
};

export default function App() {
  const [customerId, setCustomerId] = useState('demo-customer');
  const [inventory, setInventory] = useState([]);
  const [orders, setOrders] = useState([]);
  const [cart, setCart] = useState({}); // sku -> qty
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [query, setQuery] = useState('');
  const [searchResults, setSearchResults] = useState(null); // null = not searching
  const [recs, setRecs] = useState([]);

  const refresh = useCallback(async () => {
    try {
      const [inv, ord] = await Promise.all([api.listInventory(), api.listOrders(customerId)]);
      setInventory(inv);
      setOrders(ord);
      setError(null);
      return true;
    } catch (e) {
      setError(e.message);
      return false;
    }
  }, [customerId]);

  // Poll every 3s, but back off (up to 30s) while the server is failing or rate-limiting, and
  // skip polls while the tab is hidden, so a struggling free-tier backend is not hammered.
  useEffect(() => {
    let stopped = false;
    let timer;
    let delay = 3000;
    const tick = async () => {
      if (!document.hidden) {
        const ok = await refresh();
        delay = ok ? 3000 : Math.min(delay * 2, 30000);
      }
      if (!stopped) timer = setTimeout(tick, delay);
    };
    tick();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [refresh]);

  // Debounced catalogue search. Stale responses are dropped so a slow early request cannot
  // overwrite the results of a later keystroke.
  useEffect(() => {
    const text = query.trim();
    if (!text) {
      setSearchResults(null);
      return undefined;
    }
    let cancelled = false;
    const timer = setTimeout(async () => {
      try {
        const results = await api.searchInventory(text);
        if (!cancelled) setSearchResults(results);
      } catch (e) {
        if (!cancelled) setError(e.message);
      }
    }, 250);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [query]);

  const cartLines = useMemo(
    () =>
      Object.entries(cart)
        .filter(([, qty]) => qty > 0)
        .map(([sku, qty]) => ({ sku, quantity: qty, unitPrice: priceOf(sku) })),
    [cart],
  );
  const cartSkuKey = cartLines.map((l) => l.sku).sort().join(',');
  useEffect(() => {
    let cancelled = false;
    const skus = cartSkuKey ? cartSkuKey.split(',') : [];
    api
      .recommendations(skus, 8)
      .then((list) => {
        if (!cancelled) setRecs(list);
      })
      .catch(() => {
        if (!cancelled) setRecs([]); // recommendations are optional; never block the storefront
      });
    return () => {
      cancelled = true;
    };
  }, [cartSkuKey]);
  const cartTotal = cartLines.reduce((sum, l) => sum + l.unitPrice * l.quantity, 0);

  const stockBySku = useMemo(() => Object.fromEntries(inventory.map((i) => [i.sku, i])), [inventory]);
  // Search results come back ranked; show them in that order but with the freshest stock numbers.
  const visibleItems = searchResults
    ? searchResults.map((r) => stockBySku[r.sku] || r)
    : inventory;
  const suggestions = recs
    .map((r) => ({ ...r, item: stockBySku[r.sku] }))
    .filter((r) => r.item && r.item.availableQuantity > 0)
    .slice(0, 4);

  const setQty = (sku, qty) => setCart((c) => ({ ...c, [sku]: Math.max(0, qty) }));

  async function placeOrder() {
    if (cartLines.length === 0) return;
    setBusy(true);
    try {
      const order = await api.placeOrder(customerId, cartLines);
      setCart({});
      setError(
        order.status === 'REJECTED'
          ? `Order ${order.orderNumber} rejected: ${order.rejectionReason}`
          : null,
      );
      await refresh();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function act(fn) {
    setBusy(true);
    try {
      await fn();
      setError(null);
      await refresh();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page">
      <header>
        <h1>OrderFlow</h1>
        <label>
          Customer&nbsp;
          <input
            data-testid="customer-id-input"
            value={customerId}
            onChange={(e) => setCustomerId(e.target.value)}
          />
        </label>
      </header>

      {error && (
        <div className="error" data-testid="error-banner">
          {error}
        </div>
      )}

      <div className="columns">
        <section>
          <h2>Catalogue</h2>
          <input
            className="search"
            type="search"
            placeholder="Search products or SKUs (typos are fine)"
            aria-label="Search products"
            data-testid="search-input"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          {searchResults && (
            <p className="muted" data-testid="search-summary">
              {searchResults.length} result{searchResults.length === 1 ? '' : 's'} for &ldquo;
              {query.trim()}&rdquo;, best match first
            </p>
          )}
          {suggestions.length > 0 && (
            <div className="suggestions" data-testid="suggestions">
              <h3>{cartLines.length > 0 ? 'Frequently bought together' : 'Popular right now'}</h3>
              <ul>
                {suggestions.map((r) => (
                  <li key={r.sku} data-testid={`suggestion-${r.sku}`}>
                    <span>
                      {r.item.name} <code>{r.sku}</code>
                      {r.reason === 'POPULAR' && <em className="muted"> &middot; popular</em>}
                    </span>
                    <button className="secondary" onClick={() => setQty(r.sku, (cart[r.sku] || 0) + 1)}>
                      Add
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
          <table>
            <thead>
              <tr>
                <th>Product</th>
                <th className="num">Price</th>
                <th className="num">Available</th>
                <th className="num">Reserved</th>
                <th className="num">Order qty</th>
              </tr>
            </thead>
            <tbody>
              {visibleItems.map((item) => (
                <tr key={item.sku} data-testid={`inventory-row-${item.sku}`}>
                  <td>
                    <div>{item.name}</div>
                    <code>{item.sku}</code>
                  </td>
                  <td className="num">{money(priceOf(item.sku))}</td>
                  <td className="num">
                    <strong data-testid={`available-${item.sku}`}>{item.availableQuantity}</strong>
                    <button
                      className="link"
                      title="Restock +5"
                      data-testid={`restock-${item.sku}`}
                      onClick={() => act(() => api.restock(item.sku, 5))}
                    >
                      +5
                    </button>
                  </td>
                  <td className="num">{item.reservedQuantity}</td>
                  <td className="num">
                    <input
                      type="number"
                      min="0"
                      max={item.availableQuantity}
                      data-testid={`qty-input-${item.sku}`}
                      value={cart[item.sku] || 0}
                      onChange={(e) => setQty(item.sku, parseInt(e.target.value || '0', 10))}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="cart">
            <span>
              {cartLines.length} line{cartLines.length === 1 ? '' : 's'} &middot;{' '}
              <strong>{money(cartTotal)}</strong>
            </span>
            <button
              data-testid="place-order-btn"
              disabled={busy || cartLines.length === 0}
              onClick={placeOrder}
            >
              Place order
            </button>
          </div>

        </section>

        <section>
          <h2>Orders</h2>
          {orders.length === 0 && <p className="muted">No orders yet for this customer.</p>}
          <ul className="orders" data-testid="orders-list">
            {orders.map((o) => (
              <li key={o.orderNumber} data-testid={`order-${o.orderNumber}`}>
                <div className="order-head">
                  <code>{o.orderNumber}</code>
                  <span className={STATUS_CLASS[o.status] || 'badge'} data-testid="order-status">
                    {o.status}
                  </span>
                  <span className="total">{money(o.totalAmount)}</span>
                </div>
                <div className="order-lines">
                  {o.items.map((it) => (
                    <span key={it.sku}>
                      {it.quantity}&times; {it.sku}
                    </span>
                  ))}
                </div>
                {o.rejectionReason && (
                  <div className="muted" data-testid="rejection-reason">
                    {o.rejectionReason}
                  </div>
                )}
                <div className="order-actions">
                  {o.status === 'CONFIRMED' && (
                    <button
                      data-testid="ship-btn"
                      disabled={busy}
                      onClick={() => act(() => api.shipOrder(o.orderNumber))}
                    >
                      Ship
                    </button>
                  )}
                  {(o.status === 'CONFIRMED' || o.status === 'PLACED') && (
                    <button
                      data-testid="cancel-btn"
                      disabled={busy}
                      className="secondary"
                      onClick={() => act(() => api.cancelOrder(o.orderNumber))}
                    >
                      Cancel
                    </button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        </section>
      </div>

      <footer className="muted">
        Auto-refreshing every 3s. Try ordering the last unit of “Collector Edition” from two
        browser tabs at once.
      </footer>
    </div>
  );
}
