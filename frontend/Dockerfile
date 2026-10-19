# Local/CI image only — never pushed to a registry.
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
ARG VITE_API_BASE_URL=http://localhost:8080
ARG VITE_SPLIT_MIN_HOURS=16
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL VITE_SPLIT_MIN_HOURS=$VITE_SPLIT_MIN_HOURS
RUN npm run build

# Official non-root nginx (listens on 8080).
FROM nginxinc/nginx-unprivileged:stable-alpine
ARG VITE_API_BASE_URL=http://localhost:8080
ENV API_ORIGIN=$VITE_API_BASE_URL
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
