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

  const refresh = useCallback(async () => {
    try {
      const [inv, ord] = await Promise.all([api.listInventory(), api.listOrders(customerId)]);
      setInventory(inv);
      setOrders(ord);
      setError(null);
    } catch (e) {
      setError(e.message);
    }
  }, [customerId]);

  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, 3000);
    return () => clearInterval(timer);
  }, [refresh]);

  const cartLines = useMemo(
    () =>
      Object.entries(cart)
        .filter(([, qty]) => qty > 0)
        .map(([sku, qty]) => ({ sku, quantity: qty, unitPrice: priceOf(sku) })),
    [cart],
  );
  const cartTotal = cartLines.reduce((sum, l) => sum + l.unitPrice * l.quantity, 0);

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
          <input value={customerId} onChange={(e) => setCustomerId(e.target.value)} />
        </label>
      </header>

      {error && <div className="error">{error}</div>}

      <div className="columns">
        <section>
          <h2>Catalogue</h2>
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
              {inventory.map((item) => (
                <tr key={item.sku}>
                  <td>
                    <div>{item.name}</div>
                    <code>{item.sku}</code>
                  </td>
                  <td className="num">{money(priceOf(item.sku))}</td>
                  <td className="num">
                    <strong>{item.availableQuantity}</strong>
                    <button
                      className="link"
                      title="Restock +5"
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
            <button disabled={busy || cartLines.length === 0} onClick={placeOrder}>
              Place order
            </button>
          </div>
        </section>

        <section>
          <h2>Orders</h2>
          {orders.length === 0 && <p className="muted">No orders yet for this customer.</p>}
          <ul className="orders">
            {orders.map((o) => (
              <li key={o.orderNumber}>
                <div className="order-head">
                  <code>{o.orderNumber}</code>
                  <span className={STATUS_CLASS[o.status] || 'badge'}>{o.status}</span>
                  <span className="total">{money(o.totalAmount)}</span>
                </div>
                <div className="order-lines">
                  {o.items.map((it) => (
                    <span key={it.sku}>
                      {it.quantity}&times; {it.sku}
                    </span>
                  ))}
                </div>
                {o.rejectionReason && <div className="muted">{o.rejectionReason}</div>}
                <div className="order-actions">
                  {o.status === 'CONFIRMED' && (
                    <button disabled={busy} onClick={() => act(() => api.shipOrder(o.orderNumber))}>
                      Ship
                    </button>
                  )}
                  {(o.status === 'CONFIRMED' || o.status === 'PLACED') && (
                    <button
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
