// Accessibility audit: loads the built app in headless Chromium with a mocked API, runs axe-core
// (WCAG 2.0/2.1 A and AA rules) at desktop and phone widths, checks keyboard access and horizontal
// overflow, and exits non-zero if anything is found.
//   npm run build && node a11y/audit.mjs
import { createRequire } from 'node:module';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const axeSource = await readFile(require.resolve('axe-core/axe.min.js'), 'utf8');

const DIST = join(dirname(fileURLToPath(import.meta.url)), '..', 'dist');
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' };

const inventory = [
  { sku: 'SKU-KEYBOARD', name: 'Mechanical Keyboard', availableQuantity: 12, reservedQuantity: 1 },
  { sku: 'SKU-MOUSE', name: 'Wireless Mouse', availableQuantity: 0, reservedQuantity: 0 },
  { sku: 'SKU-MONITOR', name: 'Monitor 27in', availableQuantity: 4, reservedQuantity: 2 },
];
const orders = [
  { orderNumber: 'ORD-1', status: 'CONFIRMED', totalAmount: 79, items: [{ sku: 'SKU-KEYBOARD', quantity: 1 }] },
  { orderNumber: 'ORD-2', status: 'REJECTED', totalAmount: 25, rejectionReason: 'Out of stock', items: [{ sku: 'SKU-MOUSE', quantity: 1 }] },
  { orderNumber: 'ORD-3', status: 'SHIPPED', totalAmount: 329, items: [{ sku: 'SKU-MONITOR', quantity: 1 }] },
  { orderNumber: 'ORD-4', status: 'CANCELLED', totalAmount: 9, items: [{ sku: 'SKU-CABLE', quantity: 1 }] },
  { orderNumber: 'ORD-5', status: 'PLACED', totalAmount: 59, items: [{ sku: 'SKU-WEBCAM', quantity: 1 }] },
];

const server = createServer(async (req, res) => {
  const path = req.url.split('?')[0];
  try {
    const file = path === '/' ? 'index.html' : path.slice(1);
    const body = await readFile(join(DIST, file));
    res.writeHead(200, { 'Content-Type': TYPES[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404).end();
  }
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch();
const problems = [];
const summary = [];

for (const [label, viewport] of [['desktop 1280px', { width: 1280, height: 800 }], ['phone 360px', { width: 360, height: 740 }]]) {
  const page = await browser.newPage({ viewport });
  await page.route('**/api/**', (route) => {
    const url = route.request().url();
    const json = url.includes('/api/inventory') ? inventory : url.includes('/api/orders') ? orders : [];
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(json) });
  });
  await page.goto(base);
  await page.waitForSelector('[data-testid="orders-list"] li');
  await page.fill('[data-testid="qty-input-SKU-KEYBOARD"]', '2');

  await page.evaluate(axeSource);
  const results = await page.evaluate(() =>
    window.axe.run(document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] } }),
  );
  summary.push(`${label}: ${results.violations.length} axe violations, ${results.passes.length} rules passed`);
  for (const v of results.violations) {
    problems.push(`[${label}] ${v.id} (${v.impact}): ${v.help} - ${v.nodes.length} element(s), e.g. ${v.nodes[0].target.join(' ')}`);
  }

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  if (overflow > 0) problems.push(`[${label}] page scrolls horizontally by ${overflow}px`);

  // Everything interactive must be reachable with Tab and show a visible focus indicator.
  const stops = [];
  await page.focus('body');
  for (let i = 0; i < 60; i += 1) {
    await page.keyboard.press('Tab');
    const info = await page.evaluate(() => {
      const el = document.activeElement;
      if (!el || el === document.body) return null;
      const cs = getComputedStyle(el);
      const visible = cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0 || cs.boxShadow !== 'none';
      return { id: el.getAttribute('data-testid') || el.tagName, visible };
    });
    if (info) stops.push(info);
  }
  const expected = await page.evaluate(() => document.querySelectorAll('button:not([disabled]), input, a[href]').length);
  const distinct = new Set(stops.map((s) => s.id));
  const noFocusRing = [...new Set(stops.filter((s) => !s.visible).map((s) => s.id))];
  if (distinct.size < expected - 1) problems.push(`[${label}] only ${distinct.size} of ${expected} interactive elements reachable by keyboard`);
  if (noFocusRing.length) problems.push(`[${label}] no visible focus indicator on: ${noFocusRing.slice(0, 5).join(', ')}`);
  summary.push(`${label}: ${distinct.size}/${expected} interactive elements reachable by Tab, horizontal overflow ${Math.max(overflow, 0)}px`);
  await page.close();
}

await browser.close();
server.close();
console.log(summary.join('\n'));
if (problems.length) {
  console.log(`\n${problems.length} problem(s):\n- ${problems.join('\n- ')}`);
  process.exit(1);
}
console.log('\nNo accessibility problems found.');
