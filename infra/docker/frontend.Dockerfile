# syntax=docker/dockerfile:1.7
# =============================================================================
# frontend — React 18 + Vite build served by unprivileged nginx (port 8188)
# ADR-0007: nginxinc/nginx-unprivileged already runs as uid 101 and keeps its
# pid/temp files in /tmp, so listening on 8188 (> 1024) works without root.
# =============================================================================

# ----------------------------- build stage -----------------------------------
FROM node:20-alpine AS build

WORKDIR /app

# Lockfile first: `npm ci` layer is reused until dependencies change.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./
RUN npm run build

# ---------------------------- runtime stage ----------------------------------
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime

COPY infra/docker/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

USER 101

EXPOSE 8188

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8188/healthz || exit 1

ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
