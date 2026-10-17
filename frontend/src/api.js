// All calls go through the same-origin /api/* paths that the nginx gateway (prod) or the
// Vite dev proxy (local) forward to the two services.

async function request(path, options) {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const message = body?.detail || body?.title || `${res.status} ${res.statusText}`;
    const error = new Error(message);
    error.status = res.status;
    error.body = body;
    throw error;
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
