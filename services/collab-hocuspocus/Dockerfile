FROM node:24-alpine AS build
WORKDIR /workspace
RUN npm install --global pnpm@11.25.0
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml tsconfig.base.json ./
COPY services/collab-hocuspocus/package.json services/collab-hocuspocus/
COPY packages/shared-types/package.json packages/shared-types/
RUN pnpm install --filter @orbitspace/collab-hocuspocus... --frozen-lockfile
COPY contracts contracts
COPY packages/shared-types/generate.mjs packages/shared-types/tsconfig.json packages/shared-types/
COPY services/collab-hocuspocus/src services/collab-hocuspocus/src
COPY services/collab-hocuspocus/tsconfig.json services/collab-hocuspocus/
RUN pnpm --filter @orbitspace/collab-hocuspocus... build

FROM node:24-alpine
WORKDIR /workspace
COPY --from=build /workspace/node_modules node_modules
COPY --from=build /workspace/services/collab-hocuspocus services/collab-hocuspocus
USER node
EXPOSE 1234
CMD ["node", "services/collab-hocuspocus/dist/server.js"]
