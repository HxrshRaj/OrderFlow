// All calls go through the same-origin /api/* paths that the nginx gateway (prod) or the
// Vite dev proxy (local) forward to the two services.

const FRIENDLY = {
  429: 'The server is busy (too many requests). Retrying automatically…',
  502: 'The server is waking up. Retrying automatically…',
  503: 'The server is waking up. Retrying automatically…',
  504: 'The server is waking up. Retrying automatically…',
};

async function request(path, options) {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  const text = await res.text();
  // Gateways and proxies (Render, nginx) answer with plain text or HTML on 429/502/503; never
  // let JSON.parse turn that into an unreadable "Unexpected token" error.
  let body = null;
  let parsed = true;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      parsed = false;
    }
  }
  if (!res.ok) {
    const message =
      body?.detail ||
      body?.title ||
      FRIENDLY[res.status] ||
      `${res.status} ${res.statusText}`.trim();
    const error = new Error(message);
    error.status = res.status;
    error.body = body;
    throw error;
  }
  if (!parsed) {
    throw new Error('Unexpected response from the server.');
  }
  return body;
}

export const api = {
  listInventory: () => request('/api/inventory'),
  searchInventory: (q) => request(`/api/inventory/search?q=${encodeURIComponent(q)}&limit=50`),
  recommendations: (skus, limit = 4) => {
    const params = new URLSearchParams();
    skus.forEach((sku) => params.append('sku', sku));
    params.set('limit', String(limit));
    return request(`/api/recommendations?${params.toString()}`);
  },
  restock: (sku, quantityDelta) =>
    request(`/api/inventory/${encodeURIComponent(sku)}`, {
      method: 'PATCH',
      body: JSON.stringify({ quantityDelta }),
    }),
  placeOrder: (customerId, lines) =>
    request('/api/orders', {
      method: 'POST',
      body: JSON.stringify({ customerId, lines }),
    }),
  listOrders: (customerId) =>
    request(`/api/orders?customerId=${encodeURIComponent(customerId)}`),
  shipOrder: (orderNumber) =>
    request(`/api/orders/${encodeURIComponent(orderNumber)}/ship`, { method: 'POST' }),
  cancelOrder: (orderNumber) =>
    request(`/api/orders/${encodeURIComponent(orderNumber)}/cancel`, { method: 'POST' }),
};
