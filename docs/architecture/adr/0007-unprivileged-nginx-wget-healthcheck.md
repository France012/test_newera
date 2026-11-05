# ADR-0007 — Unprivileged nginx runtime and `wget` healthcheck

- **Status**: accepted
- **Date**: iteration following `2dc711a`

## Context

`eclipse-temurin:21-jre-alpine` does not ship `curl` (busybox `wget` does), and `nginx:1.27-alpine`
starts as root and needs write access to `/var/cache/nginx` and `/var/run`, so simply adding
`USER nginx` breaks the container.

## Decision

- Java images: `HEALTHCHECK CMD wget -q -O - http://127.0.0.1:<port>/actuator/health`.
- Frontend runtime image: `nginxinc/nginx-unprivileged:1.27-alpine` (uid 101, pid/temp in `/tmp`) and
  `listen 8188` — a port above 1024 — with `USER 101`.

## Consequences

- No container runs as root, satisfying the `restricted` Pod Security Standard.
- The healthcheck actually works (no missing binary) and needs no extra package installation.
- `infra/docker/nginx/default.conf` must keep `listen 8188`: lowering it below 1024 breaks the
  unprivileged bind.
