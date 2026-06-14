# syntax=docker/dockerfile:1
# web-ui — multi-stage build: Angular 20 static SPA built with Node 24, served by nginx.
# The runtime backend wiring is injected at container start (docker-entrypoint.sh), so this
# one immutable image serves any backend stack (mock or real) with no rebuild.

# ---- build stage --------------------------------------------------------------------------
FROM node:24 AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# ---- runtime stage ------------------------------------------------------------------------
FROM nginx:stable-alpine

# Static bundle (Angular @angular/build:application emits to dist/web-ui/browser).
COPY --from=build /app/dist/web-ui/browser/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/nginx.conf
COPY docker-entrypoint.sh /docker-entrypoint.d/99-acp-env.sh

# Run unprivileged. The stock nginx image ships an "nginx" user (uid 101); grant it ownership
# of the paths it must write (rendered html overlay, pid, cache) and run the master as nginx.
RUN chmod +x /docker-entrypoint.d/99-acp-env.sh \
  && touch /tmp/nginx.pid \
  && chown -R nginx:nginx /usr/share/nginx/html /tmp /var/cache/nginx \
  && chmod -R g+w /usr/share/nginx/html

USER nginx

EXPOSE 8080

# The stock nginx entrypoint runs every executable in /docker-entrypoint.d (including our
# env-overlay generator) before launching nginx in the foreground.
ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
