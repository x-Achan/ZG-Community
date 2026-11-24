# 知光 ZG-Community

一个面向"知识分享"场景的图文社区后端：注册登录、发帖、关注、点赞收藏、个性化 Feed 流与热榜，全部围绕**高并发读写**设计。
Java 17 + Spring Boot 3.4.1，MySQL / Redis / Kafka / Canal 四件套，Caffeine 做进程内 L1 缓存，JWT（RS256）做无状态鉴权。

> 前端 UI 在独立仓库 `zhiguang_fe-main`（Vite，默认 `5173`）；本仓库是纯后端。

---

## 目录

- [功能一览](#功能一览)
- [技术栈](#技术栈)
- [高并发设计](#高并发设计)
- [环境启动](#环境启动)
- [仓库结构](#仓库结构)
- [接口速查](#接口速查)
- [约定与注意事项](#约定与注意事项)

---

## 功能一览

| 模块 | 能力 |
|---|---|
| **认证 `auth`** | 手机号/邮箱验证码注册登录、密码登录、`access(15m) + refresh(7d)` 双令牌、刷新、登出、重置密码、`GET /me` |
| **知识帖 `knowpost`** | 草稿创建 → 内容确认（对象存储直传后回填）→ 编辑/置顶/可见性/删除、帖子详情、热榜 |
| **Feed `feed`** | 关注流（推拉结合 + 收件箱 ZSet）、我的帖子、作者主页、热门/公共候选池；游标分页（无 offset） |
| **计数 `counter`** | 点赞、收藏；Redis 侧同步计数 + Kafka 异步明细落库 + 定时对账校准 |
| **关系 `relation`** | 关注/取关、关系状态、关注/粉丝列表、关注数与粉丝数；Canal outbox → 缓存投影 + 接口级令牌桶限流 |
| **主页 `profile`** | 资料读取/局部更新、头像上传（OSS 预签名直传） |
| **存储 `storage`** | 阿里云 OSS 预签名上传 URL，客户端直传，服务端不中转文件 |

---

## 技术栈

| 层 | 选型 |
|---|---|
| 语言/运行时 | Java 17 |
| 框架 | Spring Boot 3.4.1（Web / Validation / Data Redis / Kafka / Security OAuth2 Resource Server） |
| 持久层 | MyBatis 3.0.3 + MySQL 8（`mysql-connector-j`），XML Mapper，HikariCP（`maximum-pool-size=50`） |
| 缓存 | Redis（Lettuce 连接池）+ **Caffeine** 进程内 L1；**Redisson** 3.52 分布式锁（对账任务单实例执行） |
| 消息 | Kafka（生产 `acks=all` + 幂等 + `linger.ms=5`；消费 batch listener + 手动 ack + `max-poll-records=500`） |
| 变更订阅 | **Canal**（Kafka 模式，`canal-outbox` 主题）→ 关系缓存投影消费者 |
| 鉴权 | JWT **RS256**（`private.pem` / `public.pem`，`kid=zhiguang-key`）+ BCrypt（strength 12）+ Redis 刷新令牌存储 |
| 对象存储 | 阿里云 OSS SDK 3.17.3（预签名直传） |
| 构建 | Maven（`spring-boot-maven-plugin`） |
| 前端（独立仓库） | Vite + npm（开发端口 5173） |

---

## 高并发设计

这一节是项目的主体。每一条都对应一个**具体的瓶颈**和**可量化的前后对比**，代码注释里记录了实测数据。

### 1. 计数：同步加 Redis，异步落明细，再对账兜底

- **写路径**：点赞/收藏先在 Redis 原子自增（Lua 脚本 `INCR_IF_EXISTS`，判断存在 + 自增一步完成），再由 Kafka 生产者发事件。
- **明细落库**：消费者把一批事件**按最终状态合并**后做一次多值 `INSERT`。
  - 改造前：200 个并发点赞 = 200 条单行 INSERT，在唯一索引 `uk_user_post_type` 上争 gap 锁 —— **实测 238 次行锁等待、721 ms**；
  - 改造后：200 条事件 = 1 次多值 INSERT，锁只加一次 —— **实测 35 ms、锁等待归零**。
- **幂等**：明细表靠 `uk_user_post_type(user_id, post_id, type)` + `INSERT IGNORE` 天然幂等，Kafka 重复投递不产生重复行，所以消费端**不需要去重表**。
- **对账**：`CounterReconcileTask` 每 5 分钟（首次延迟 30 s）用 Redisson 锁保证**单实例**执行；只做**差值修正**而不是 `SET` 覆盖，`tolerance=1000` 把"在途未落库"的差视为正常（`CounterReconcileDeltaTest` 专门覆盖"修正期间来了并发点赞"这个坑）。
- **读路径**：计数值带 `base-ttl=7200s ± jitter 1200s` 的随机过期，避免同一时刻大面积穿透。

### 2. Feed：推拉结合 + 收件箱 ZSet + 两级缓存 + 游标分页

- **推**：作者发帖 → 投影事件 → 写入粉丝**收件箱 ZSet**（`score = create_time` 毫秒），只对有活跃关注关系的粉丝推送。
- **拉**：收件箱按需重建（上限 1000 条），未命中时从 MySQL 重建，索引 `idx_feed_author` / `idx_feed_public_candidate` 专门为游标排序设计。
- **两级缓存**：Caffeine L1（进程内）+ Redis L2 —— 页面快照 `120s`、单帖快照 `7200s` 带抖动。
- **游标分页**：`(sortKey, id)` 复合游标，不用 `offset`，深翻页不退化；热榜游标带 `version`，旧榜保留 30 分钟，翻页过程中榜单更新不会让用户"跳页"。
- **扇出可控**：收件箱有 `trim` 上限，避免大 V 一条帖子把 Redis 写爆。

### 3. 关系：outbox + Canal，缓存只删不写

- 关注/取关先写业务表，同一事务内写 `outbox`；Canal 订阅 binlog 推到 `canal-outbox`，`RelationEventConsumer` 做投影。
- **缓存策略是"删"而不是"改"**：关系变化后直接删除相关 ZSet 分页缓存，下一次读从 MySQL 重建 —— 避免并发下写-写覆盖，也天然兼容重复/乱序消息。
- **计数不从消息增减**：投射时按数据库真实值刷新，防重复消息导致的计数漂移（`RelationCounterReconcileTask` 做最终校准）。
- **接口级限流**：`TokenBucketRateLimiter` 用 Lua 实现原子令牌桶，桶状态存 Redis Hash（`last` / `tokens`）；**时间取 Redis `TIME` 而不是 Java 系统时钟**，否则多实例时钟快慢不一致会让限流形同虚设。

### 4. 写路径可靠性

- Kafka 生产端 `acks=all` + `enable.idempotence=true` + `retries=3`：多花 1~3 ms，换来"用户拿到成功响应时事件一定已经落盘"。
- 消费端 `enable-auto-commit=false` + 手动 ack + batch listener：处理完才提交位点，进程崩溃只会重放，不会丢。
- ID 用**雪花算法**（`SnowflakeIdGenerator`），对 NTP 小幅回拨做等待重试，避免生成回退 ID。

### 5. 热榜

`score = interaction / (ageHours + 2)^1.5` —— 交互量做分子、时间做 1.5 次幂衰减，写进 Redis ZSet；按事件增量更新，不扫全表。

---

## 环境启动

### 前置依赖

| 组件 | 版本 | 用途 | 端口 |
|---|---|---|---|
| JDK | 17+ | 运行 | — |
| Maven | 3.8+ | 构建 | — |
| MySQL | 8.x | 主库 | 3306 |
| Redis | 5+（需 Lua、`TIME`） | 缓存/计数/限流/收件箱 | 6379 |
| Kafka | 3.x（KRaft 可） | 计数事件、Feed 投影 | 9092 |
| Canal | 1.1.x（Kafka 模式） | 关系 outbox 订阅（**可选**，缺省时关系缓存退化为直连 DB 重建） | 11111/11112 |
| Node.js | 18+（仅前端） | 前端 | 5173 |

### 1. 初始化数据库

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS zhiguang DEFAULT CHARSET utf8mb4;"
mysql -uroot -p zhiguang < db/schema.sql     # 建表：users / login_logs / know_posts / outbox / following / follower / like_record
mysql -uroot -p zhiguang < db/feed_v1.sql    # Feed 游标分页所需的复合索引
```

### 2. 配置

编辑 `src/main/resources/application.yaml`（或用同名环境变量覆盖）：

```yaml
spring.datasource.url: jdbc:mysql://localhost:3306/zhiguang?...#rewriteBatchedStatements=true  # 批量落库必须
spring.datasource.username / password
spring.data.redis.host / port / password
spring.kafka.bootstrap-servers: localhost:9092
counter.kafka.topic: counter-events
relation.outbox.topic: canal-outbox
oss.access-key-id / access-key-secret   # 走环境变量 OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET
oss.bucket / endpoint
```

> `rewriteBatchedStatements=true` 不能省：漏了之后 JDBC 批处理会退化成 N 次往返，多值 INSERT 的收益直接归零。

### 3. 一键（Windows）

仓库自带 `start-dev.ps1`（`start-dev.cmd` 是它的双击包装）：按顺序拉起 Redis / Kafka / Canal → 前端 → 后端，并把日志写到 `.dev-logs/`。

```powershell
# 先看配置与端口状态，不启动任何进程
.\start-dev.cmd -CheckOnly

# 正常启动（MySQL 需事先运行）
.\start-dev.cmd
```

脚本内的工具路径（Redis / Kafka / Canal / JDK / 前端根目录）是作者本机路径，**换机器需要先改文件顶部的 `$redisRoot`、`$kafkaRoot`、`$canalRoot`、`$javaRoot`、`$frontendRoot`**。

### 4. 手动启动（跨平台）

```bash
# 1) Redis
redis-server
# 2) Kafka（KRaft 单机）
bin/kafka-server-start.sh config/kraft/server.properties
# 3) Canal（可选，Kafka 模式；配置 instance 指向业务库并把 topic 设为 canal-outbox）
bin/startup.sh
# 4) 后端
mvn spring-boot:run
# 默认 http://localhost:8080 ，接口前缀 /api/v1
```

### 5. 启动自检

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/send-code \
  -H 'Content-Type: application/json' \
  -d '{"identifierType":"PHONE","identifier":"13800000000","scene":"REGISTER"}'
# 返回 200 + 验证码已发送（开发环境验证码见 Redis 键或日志）
```

端口自检清单：`3306` MySQL、`6379` Redis、`9092` Kafka、`11112` Canal（Kafka 模式就绪按 metrics 端口判断，`11111` 客户端端口不可靠）、`8080` 后端、`5173` 前端。

### 6. 跑测试

```bash
mvn test
# 关注两个特殊用例：
#   CounterBenchmarkTest      —— 热行点赞压测（输出 avg / p99）
#   CounterReconcileDeltaTest —— 对账期间并发点赞不被覆盖
```

---

## 仓库结构

```text
ZG-Community/
├── pom.xml                      # Spring Boot 3.4.1 / Java 17 / 依赖清单
├── start-dev.cmd / .ps1         # Windows 一键开发环境（Redis/Kafka/Canal/前后端）
├── db/
│   ├── schema.sql               # 全部建表 + 唯一索引
│   └── feed_v1.sql              # Feed 游标分页复合索引
├── docs/
│   └── interview/               # 面试官 system prompt 与背景资料（项目问答演练用）
├── artifacts/feed-flow-vibe/    # 一次性演示产物（渲染脚本 + 音频 + QA 截图，含 ffmpeg 二进制）
└── src/
    ├── main/java/com/zhiguang/
    │   ├── ZhiguangApplication.java
    │   ├── auth/                # 注册登录、验证码、JWT(RS256)、刷新令牌、安全配置
    │   ├── config/              # Redisson
    │   ├── counter/             # 点赞收藏：Lua 计数、Kafka 事件、批量落库、对账任务
    │   ├── feed/                # Feed：收件箱 ZSet、推拉投影、Caffeine+Redis 两级缓存、游标
    │   ├── knowpost/            # 帖子：草稿/确认/编辑/置顶/可见性、雪花 ID、热榜
    │   ├── profile/             # 个人主页
    │   ├── relation/            # 关注关系：outbox、Canal 消费、投影、令牌桶限流、对账
    │   ├── storage/             # OSS 预签名直传
    │   └── user/                # 用户实体与 Mapper
    ├── main/resources/
    │   ├── application.yaml     # 数据源/Redis/Kafka/鉴权/OSS/计数与关系参数
    │   ├── mapper/*.xml         # MyBatis XML（KnowPost / LikeRecord / Relation / User / Outbox）
    │   └── keys/{private,public}.pem  # 开发用 JWT 密钥对
    └── test/java/com/zhiguang/  # 压测与对账回归用例
```

分层约定：每个业务包都是 `api`（Controller + DTO）/ `service`（接口 + `impl`）/ `mapper`（Mapper + XML）/ `model` 或 `schema`（实体、Redis 键名）/ `event`、`task`、`cache`（异步与缓存横切）。

---

## 接口速查

| 前缀 | 说明 |
|---|---|
| `POST /api/v1/auth/send-code` `register` `login` `token/refresh` `logout` `password/reset`、`GET /me` | 认证 |
| `POST /api/v1/knowposts/draft`、`POST /{id}/content/confirm`、`PATCH /{id}/patch` `/{id}/top` `/{id}/visible`、`DELETE /delete/{id}` | 帖子 |
| `GET /api/v1/knowposts/feed`、`/feed/mine`、`/feed/users/{authorId}`、`/feed/following` | Feed（游标分页） |
| `POST /api/v1/counter/{postId}/like`、`DELETE .../like`、`POST .../fav`、`DELETE .../fav` | 点赞/收藏 |
| `POST /api/v1/relation/follow` `unfollow`、`GET /status` `/following` `/followers` `/counter` | 关注关系 |
| `PATCH /api/v1/profile/patch`、`POST /api/v1/profile/avatar` | 个人主页 |
| `POST /api/v1/storage/presign` | 获取 OSS 直传地址 |

鉴权：除 `auth` 白名单外均需 `Authorization: Bearer <access_token>`。

---

## 约定与注意事项

- **密钥**：`src/main/resources/keys/*.pem` 是**开发用**密钥对，随仓库提供只为让本地开箱即跑；生产环境请替换并改为外部注入（KMS / 环境变量），不要直接复用。
- **敏感配置**：数据库口令、Redis 口令、OSS AK/SK 建议全部走环境变量；当前 `application.yaml` 里的明文值仅用于本地。
- **Canal 可选**：未部署 Canal 时，关系缓存不会收到投影事件，表现为"关注后列表刷新变慢但仍正确"（读路径会回源重建）。
- **`artifacts/`**：历史演示产物，不影响后端构建；若在意仓库体积可以单独清理。
- **协议**：仅供学习与作品展示使用，未附带开源许可证。

---

## 后续计划

- 关注流支持"大 V 拉模式"自动降级（当前靠收件箱 `trim` 上限保护）
- 计数明细冷热分离（按 `create_time` 归档历史 `like_record`）
- 补充 OpenAPI 文档与压测脚本（当前压测在 `CounterBenchmarkTest` 内）
