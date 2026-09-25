# cc-fisheries

管理捕捞配额、权属变化和卸港申报。

## 主要业务规则

### 配额账户

- 账户按 **捕捞季 + 物种 + 权利人** 组合唯一管理（数据库唯一约束），开立时核准入账。
- 所有数量使用固定精度 `BigDecimal`（3 位小数），超精度输入直接拒绝。
- 账户分别维护三个余额：**可用（available）**、**转让冻结（frozen）**、**已核销（consumed）**；
  任何操作后 `可用 + 冻结 + 已核销` 守恒，且任何余额不得为负。

### 配额转让

- 转让方发起转让时，从其可用量中冻结相应数量（可用不足则拒绝）。
- 受让方接受后，在**同一事务**内扣减转让方冻结量、增加受让方可用量；
  受让方必须在同一捕捞季/物种下持有账户，因此转让不能跨捕捞季或物种。
- 受让方拒绝或转让到期时，冻结量**只释放一次**（状态机 `PENDING → ACCEPTED/REJECTED/EXPIRED`，
  已终结的转让不能再次变更）；到期释放由定时任务或 `POST /api/transfers/expire-due` 触发，
  在独立事务中提交。

### 卸港申报

- 申报包含唯一事件号、渔船、权利人、物种、捕捞季和重量，只扣减权利人的**可用**配额并计入已核销。
- 事件号全局唯一：相同事件号、相同内容的重放幂等返回原记录，不重复核销；
  内容不同返回 `409 Conflict`；并发下同事件号由数据库唯一约束兜底。

### 并发与台账

- 所有余额变更先取账户行的**悲观写锁**（`SELECT ... FOR UPDATE`），转让单状态迁移持有转让单行锁；
  双向转让并发时按固定顺序加锁避免死锁，保证不超转、不超捕、不重复释放。
- 每次余额变化写入一条**不可变台账事件**（含变化后三余额快照及关联单号）；
  失败请求事务回滚，不写入台账。

### 主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/quota-accounts` | 开立账户并核准入账 |
| GET | `/api/quota-accounts`、`/api/quota-accounts/{id}` | 账户查询 |
| GET | `/api/quota-accounts/{id}/ledger` | 账户台账 |
| POST | `/api/transfers` | 发起转让（冻结） |
| POST | `/api/transfers/{id}/accept`、`/reject` | 接受 / 拒绝 |
| POST | `/api/transfers/expire-due` | 处理到期转让 |
| GET | `/api/transfers/{id}`、`/api/transfers` | 转让查询 |
| POST | `/api/landings` | 卸港申报（幂等） |
| GET | `/api/landings/{eventId}`、`/api/landings` | 卸港记录查询 |

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Maven Wrapper 3.9.9
- H2

## 本地运行

启动服务：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test
