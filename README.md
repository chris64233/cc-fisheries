# cc-fisheries

管理捕捞配额、权属变化和卸港申报。

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

## 主要业务规则

### 配额账户

- 账户按 **捕捞季 + 物种 + 权利人** 唯一管理（数据库唯一约束），创建时写入核准数量。
- 所有数量使用 `BigDecimal`，固定 3 位小数；账户分别维护 **可用（available）**、**转让冻结（transferFrozen）**、**已核销（consumed）** 三个余额，任何余额都不允许为负。

### 配额转让

- 转让方发起转让时，从可用余额中冻结相应数量（`available → transferFrozen`），生成 PENDING 状态的转让单并携带到期时间。
- 受让方接受后，在 **同一事务** 中扣减转让方冻结量并增加受让方可用量；受让方账户不存在时自动开立零余额账户。
- 拒绝或到期时释放冻结量回可用余额；转让单状态在悲观锁保护下只能从 PENDING 迁移一次，因此 **冻结量只会释放一次**。
- 转让严格限定在同一捕捞季、同一物种内（账户按组合锁定），转让方与受让方不能相同，冻结量不能超过可用余额。

### 卸港申报

- 申报包含唯一事件号、渔船、权利人、物种、捕捞季和重量，只扣减权利人的 **可用** 配额并计入已核销。
- 相同事件号且内容完全相同的重放请求幂等返回原记录（HTTP 200，`replayed=true`），不重复核销；事件号相同但内容不同返回 409 冲突。
- 可用余额不足时申报失败，账户余额、卸港记录和台账均不发生任何变化。

### 并发与台账

- 账户行和转让单均使用 JPA 悲观写锁（`PESSIMISTIC_WRITE`）串行化并发变更，配合唯一约束防止超转、超捕和重复释放。
- 每次余额变化追加一条 **不可变台账事件**（类型、数量、变化后三余额快照、关联单据号）；失败请求随事务回滚，不写入台账。

## API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/quota-accounts` | 开立配额账户（核准数量） |
| GET | `/api/quota-accounts/{id}` | 查询账户余额 |
| GET | `/api/quota-accounts/lookup?season=&species=&holder=` | 按组合查询账户 |
| GET | `/api/quota-accounts/{id}/ledger` | 查询账户台账 |
| POST | `/api/transfers` | 发起转让（冻结可用配额，可传 `ttlSeconds`） |
| POST | `/api/transfers/{id}/accept` | 受让方接受转让 |
| POST | `/api/transfers/{id}/reject` | 转让双方拒绝/撤销转让 |
| POST | `/api/transfers/{id}/expire` | 到期释放（幂等） |
| GET | `/api/transfers/{id}` | 查询转让详情 |
| POST | `/api/landings` | 卸港申报核销（事件号幂等） |
| GET | `/api/landings/{eventId}` | 按事件号查询卸港记录 |
| GET | `/api/landings?holder=` | 按权利人查询卸港记录 |
