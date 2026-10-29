# 知光 Feed 流视频：事实来源表

> 核验日期：2026-10-22。项目事实以当前工作区源码为准；底层数据结构与框架语义以官方文档交叉核验。当前 Feed 改动尚未提交，视频中会明确称为“当前实现”，不称为“线上已运行架构”。

| 事实 ID | 拟呈现说法 | 项目源码定位 | 外部原理来源 | 核验结论 |
|---|---|---|---|---|
| F01 | Feed 有热门、我的、作者主页、关注流四个入口 | `FeedController.java:22,36,52,66` | — | 已核验 |
| F02 | 四个入口最终复用同一套“取候选 → 权限过滤 → 页面快照 → 动态补齐”的读页流程 | `FeedServiceImpl.java:38-129,132-245` | — | 已核验 |
| F03 | 作者流、关注收件箱和热门榜都以 Redis ZSet 维护有序候选；score 为时间或热度 | `FeedIndexService.java:35-64,283-323`、`HotRankService.java:96-106` | [Redis Sorted Sets](https://redis.io/docs/latest/develop/data-types/sorted-sets/) | 已核验；ZSet 成员按 score 排序，适合有序索引 |
| F04 | 翻页游标由 kind、version、score、postId 组成，并经 URL-safe Base64 编码 | `FeedCursor.java:8-35`、`FeedServiceImpl.java:250-284` | — | 已核验 |
| F05 | 热门 Feed 把榜单 version 固定进游标，翻页继续读同一版本；旧榜保留 30 分钟 | `FeedServiceImpl.java:38-50`、`HotRankService.java:107-117` | — | 已核验 |
| F06 | 页面缓存只存帖子 ID 顺序、nextCursor 与 hasMore；帖子内容另存片段，缺失时批量回源数据库 | `FeedPageSnapshot.java:7-13`、`FeedCacheService.java:36-119` | [Redis MGET](https://redis.io/docs/latest/commands/mget/) | 已核验；MGET 一次返回多个 key，复杂度 O(N) |
| F07 | 页面缓存为 Caffeine 30 秒 + Redis 120–150 秒；帖子片段为 Redis 7200–7800 秒，随机抖动降低同刻过期风险 | `FeedCacheConfig.java:13-19`、`FeedCacheService.java:32-33,57-62,112-117` | [Caffeine Eviction](https://github.com/ben-manes/caffeine/wiki/Eviction)、[Redis Key Expiration](https://redis.io/docs/latest/develop/using-commands/keyspace/) | 已核验 |
| F08 | 页面命中后仍会重新校验可见性，并批量补齐点赞数、收藏数、当前用户是否已点赞/收藏 | `FeedServiceImpl.java:194-245` | — | 已核验；这使公共缓存不混入用户私有状态 |
| F09 | 关注流采用混合 fan-out：普通作者发布时推到粉丝 inbox；粉丝数达到 10000 的大 V 在读取时拉取作者索引再合并 | `FeedIndexService.java:25,40-61,113-131,176-204` | — | 已核验；“推/拉”是对代码行为的概括 |
| F10 | 帖子与关注关系变更先写 Outbox，再经 Canal/Kafka 消费生成 Feed 投影；消费者处理成功后手动 ack | `KnowpostServiceImpl.java:276-307`、`PostProjectionEventConsumer.java:27-45`、`RelationEventProcessor.java:46-49` | [Debezium Outbox Event Router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)、[Spring Kafka Listeners](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/message-listeners.html) | 已核验；项目使用自定义 Outbox + Canal，外部文档用于核验模式与 ack 语义 |
| F11 | 投影消费者不信任事件旧快照，而是重新读取数据库当前状态，再更新索引和缓存 | `FeedProjectionService.java:25-62` | — | 已核验 |
| F12 | 热门榜每分钟重建，候选最多 20000 篇、最近 30 天；分数为 `(点赞×3 + 收藏×5 + 1) / (年龄小时 + 2)^1.5` | `HotRankService.java:26-27,45-48,62-105,126-143` | — | 已核验；这是当前实现公式，不宣称为通用最优公式 |
| F13 | Feed 回源相关查询增加了作者、公开候选、关注与粉丝方向的复合索引 | `db/feed_v1.sql:1-33` | [MySQL Multiple-Column Indexes](https://dev.mysql.com/doc/refman/8.4/en/multiple-column-indexes.html) | 已核验；官方文档说明复合索引按左前缀使用 |
| F14 | 前端把 nextCursor 带回接口，并把下一页 items 追加到当前瀑布流 | `knowpostService.ts:73-79,118-128`、`HomePage.tsx:17-30,91-94` | — | 已核验 |

## 核验边界

- `mvn test` 未执行：当前机器未安装 Maven，仓库也没有 Maven Wrapper。因此本轮只做静态源码核验，不能把“可编译、已通过测试”写进视频。
- 当前工作树包含大量未提交 Feed 改动。视频展示的是实现设计与代码路径，不代表生产环境压测结论。
- 外部真实图片不作为视频素材使用；所有架构图、代码卡片、队列、游标与缓存图形均由代码自绘，避免许可不明。
