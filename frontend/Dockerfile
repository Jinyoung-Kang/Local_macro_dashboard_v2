# 프런트엔드 이미지 (Next.js)
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .

# 백엔드 주소는 빌드 시점에 번들에 들어갑니다(NEXT_PUBLIC_ 접두사).
ARG NEXT_PUBLIC_API_BASE=http://localhost:8080
ENV NEXT_PUBLIC_API_BASE=$NEXT_PUBLIC_API_BASE

# 이 이미지가 "어느 코드"로 빌드됐는지 화면에 새겨 둡니다(브랜치@커밋).
# git pull을 했는데 화면이 그대로일 때, 코드를 안 받은 것인지 화면이 안 바뀐
# 것인지 구분할 방법이 없었습니다. 값은 make가 넣어 줍니다(없으면 빈 값).
ARG NEXT_PUBLIC_APP_VERSION=
ENV NEXT_PUBLIC_APP_VERSION=$NEXT_PUBLIC_APP_VERSION

RUN npm run build

FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production TZ=Asia/Seoul

COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/.next ./.next
COPY --from=build /app/public ./public

EXPOSE 3000
CMD ["npm", "run", "start"]
