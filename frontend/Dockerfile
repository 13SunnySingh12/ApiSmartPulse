# Local/CI image only — never pushed to a registry.
FROM node:26-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
ARG VITE_API_BASE_URL=http://localhost:8080
ARG VITE_SPLIT_MIN_HOURS=16
# local = built-in login; neon = Neon Auth (needs the branch's public Auth URL, not a secret)
ARG VITE_AUTH_PROVIDER=local
ARG VITE_NEON_AUTH_URL=
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL VITE_SPLIT_MIN_HOURS=$VITE_SPLIT_MIN_HOURS     VITE_AUTH_PROVIDER=$VITE_AUTH_PROVIDER VITE_NEON_AUTH_URL=$VITE_NEON_AUTH_URL
RUN npm run build

# Official non-root nginx (listens on 8080).
FROM nginxinc/nginx-unprivileged:stable-alpine
ARG VITE_API_BASE_URL=http://localhost:8080
ARG NEON_AUTH_ORIGIN=
ENV API_ORIGIN=$VITE_API_BASE_URL NEON_AUTH_ORIGIN=$NEON_AUTH_ORIGIN
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
