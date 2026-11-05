# 云购商城优化

基于 waynboot 开源商城学习与二次开发，集中管理 Java 后端、管理后台前端和商城前端，记录后续每次优化及验证。

## 项目结构

| 目录 | 用途 |
| --- | --- |
| waynboot-mall | Java 17 / Spring Boot 多模块后端 |
| waynboot-admin | Vue 管理后台 |
| waynboot-mobile | Vue 商城用户端 |

## 来源与许可证

- 后端：https://github.com/wayn111/waynboot-mall
- 管理端：https://github.com/wayn111/waynboot-admin
- 用户端：https://github.com/wayn111/waynboot-mobile

本仓库导入本地三个仓库的已提交版本作为学习基线，并非全部原创。各子目录保留原有 LICENSE 和版权声明。来源版本见 [BASELINE.md](BASELINE.md)。本地运行配置不上传，`.example` 为脱敏参考配置，使用前复制去掉 `.example` 并配置本地依赖；它们不是直接可用的生产配置。

## 本地运行

先启动 Docker Desktop，在仓库根目录运行：

```powershell
docker compose -f compose.waynboot-local.yml up -d
docker compose -f compose.waynboot-local.yml ps
```

已有学习数据不需要重新初始化。全新数据卷需要按后端 db-init 脚本依赖顺序初始化。

IDEA 导入 `waynboot-mall/pom.xml`，使用 JDK 17，分别运行 `AdminApplication`（81）、`MobileApplication`（82）、`MessageApplication`（85）。后端本地配置需匹配 Compose：MySQL 13306、Redis 6379、RabbitMQ 5672、ES 9200。日志和上传目录通过 `LOG_PATH_PREFIX`、`UPLOAD_DIR` 环境变量配置。

两个 PowerShell 窗口分别运行：

```powershell
Set-Location ./waynboot-admin
npm.cmd run dev
```

```powershell
Set-Location ./waynboot-mobile
npm.cmd run dev
```

首次在新环境运行前需安装依赖。访问管理端 http://localhost:9530/admin/ ，商城 http://localhost:4949/mall/ 。

## 优化记录

当前进度：

1. 已实现异步下单结果查询：用户归属校验、MySQL 成功兜底、处理中/成功/未知状态及有限轮询；单元测试和前端构建通过，真实页面联调待验证。
2. 待实施：Outbox 与 RabbitMQ Confirm / Return / 超时重试闭环。
3. 待实施：重复消费、支付与关单竞争、库存补偿的并发及故障验证。

每次优化使用一个独立提交，记录问题、修改内容和实际验证结果，见 [CHANGELOG.md](CHANGELOG.md)。
