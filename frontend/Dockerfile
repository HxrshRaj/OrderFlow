# --- build the SPA ---
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install
COPY . .
RUN npm run build

# --- serve it + proxy the API ---
FROM nginx:1.27-alpine
# Defaults suit a plain `docker run`; docker-compose and render.yaml override these.
ENV PORT=80 \
    PROXY_SCHEME=http \
    INVENTORY_UPSTREAM_HOST=inventory-service:8081 \
    ORDER_UPSTREAM_HOST=order-service:8080 \
    NGINX_ENVSUBST_FILTER=(PORT|PROXY_SCHEME|INVENTORY_UPSTREAM_HOST|ORDER_UPSTREAM_HOST)
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 80
