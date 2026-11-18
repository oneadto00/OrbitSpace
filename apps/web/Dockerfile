FROM node:24-alpine AS build
WORKDIR /workspace
RUN npm install --global pnpm@11.25.0
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml tsconfig.base.json ./
COPY apps/web/package.json apps/web/
COPY apps/cli/package.json apps/cli/
COPY packages/shared-types/package.json packages/shared-types/
RUN pnpm install --frozen-lockfile
COPY contracts contracts
COPY packages/shared-types/generate.mjs packages/shared-types/tsconfig.json packages/shared-types/
COPY apps/web/src apps/web/src
COPY apps/web/index.html apps/web/vite.config.ts apps/web/tsconfig.json apps/web/
RUN pnpm --filter @orbitspace/web... build

FROM nginx:stable-alpine
COPY --from=build /workspace/apps/web/dist /usr/share/nginx/html
RUN printf '%s\n' 'server {' '  listen 80;' '  root /usr/share/nginx/html;' '  location /assets/ { try_files $uri =404; }' '  location / { try_files $uri /index.html; }' '}' > /etc/nginx/conf.d/default.conf
