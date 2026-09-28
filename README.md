# cc-fisheries

管理捕捞配额、权属变化和卸港申报。

## 主要业务规则

### 配额账户

- 账户按 **捕捞季 + 物种 + 权利人** 组合唯一管理（数据库唯一约束），开立时核准入账。
- 所有数量使用固定精度 `BigDecimal`（3 位小数），超精度输入直接拒绝。
- 账户分别维护三个余额：**可用（available）**、**冻结（frozen）**、**已核销（consumed）**；
  任何操作后 `可用 + 冻结 + 已核销` 守恒，且任何余额不得为负。
- 账户带 JPA 乐观版本号 `version`，每次余额变更递增，供更正等决策做版本校验。
- 每一笔冻结都对应一条**冻结明细（quota_hold）**，区分转让 / 卸港 / 更正来源，
  状态为 `HELD → SETTLED/RELEASED`；任一账户所有 `HELD` 明细数量之和恒等于其 `frozen` 余额。

### 配额转让

- 转让只允许发生在**同一捕捞季、同一物种**的许可证之间（账户自然键保证，不能跨捕捞季或物种）。
- 转让方发起转让时，从其**可转余额（即可用量）**中冻结相应数量并写入一条转让冻结明细；
  已上岸（已核销）数量、待复核（卸港 / 增重更正）冻结、以及被**其他转让申请**占用的数量
  都不能再转让。可用量不足直接拒绝，不落任何记录。
- 发起、接受、拒绝、取消都必须携带**外部请求号（requestId）**，请求号按动作分别命名空间
  （`TRANSFER-CREATE / -ACCEPT / -REJECT / -CANCEL`）登记到幂等决策表：
  - 相同请求号 + 相同内容重放（含并发）只生效一次，返回原转让单，不重复冻结 / 划转 / 释放；
  - 相同请求号但申请内容不同、或作用于不同转让单，返回 `409 Conflict`；
  - 并发重复提交由数据库唯一约束兜底，获胜事务提交后其余请求自动重放。
- 受让方接受后，在**同一事务**内扣减转让方冻结量、增加受让方可用量，冻结明细置为 `SETTLED`，
  任何一步失败整体回滚，不会只改一方；受让方必须在同一捕捞季/物种下持有账户，否则接受失败。
- 转让方可在接受前**主动取消**（`POST /api/transfers/{id}/cancel`，需声明转让方本人），
  受让方拒绝、转让到期同样释放冻结；冻结量**只释放一次**
  （状态机 `PENDING → ACCEPTED/REJECTED/CANCELLED/EXPIRED`，冻结明细置为 `RELEASED`，
  已终结的转让不能再次变更）；到期释放由定时任务或 `POST /api/transfers/expire-due`
  触发，在独立事务中提交。
- **可转余额查询**：`GET /api/quota-accounts/transfer-availability?season=&species=&holder=`
  返回该许可证的数量分解，申请期间可清楚区分：
  - `available` / `transferable`：当前可转余额；
  - `consumed` / `landed`：已上岸（已核销）数量，不可转让；
  - `frozen` / `occupiedByOthers`：被其他申请暂时占用的冻结总量，并进一步拆分为
    `transferHeld`（其他转让申请）、`landingHeld`（卸港待复核）、`correctionHeld`（更正待复核）；
  - 查看某笔在途转让时传 `excludeTransferId`，该笔自身冻结算回可转量，占用只统计“其他申请”。
- **转让详情 / 余额核对**：`GET /api/transfers/{id}/detail` 返回单据状态与全部时间
  （创建 / 到期 / 终结），以及双方许可证各自的：
  - `before` / `after` 三余额快照（转让前、转让后）；
  - 与本转让相关的台账事件（`TRANSFER_FREEZE / TRANSFER_OUT / TRANSFER_IN / TRANSFER_RELEASE`），
    每条事件都带事件前后余额与发生时间；
  - 转让冻结明细（仅转让方，`HELD / SETTLED / RELEASED`）。
  据此可核对：转让方减少量 = 受让方增加量 = 转让数量，冻结明细与双方台账一一对应。

### 卸港申报：先冻结，复核后核销

- 申报包含唯一事件号、渔船、权利人、物种、捕捞季和重量。申报成功后进入
  **待复核（PENDING_REVIEW）**：从权利人**可用**量冻结申报重量，写入 `LANDING_FREEZE` 台账和
  一条 `LANDING` 冻结明细，此时**尚未核销**。
- 港口复核人用独立的**复核事件号**确认：
  - `POST /api/landings/{eventId}/confirm`：冻结量正式核销（`frozen → consumed`，
    `LANDING_CONSUME`），记录已确认重量；
  - `POST /api/landings/{eventId}/reject`：冻结量释放回可用（`frozen → available`，
    `LANDING_RELEASE`）。
- 冻结、确认、释放每一步都只在两个余额间等量平移，保证可用量、冻结量、已核销量守恒且不为负。
- 申报事件号全局唯一：相同事件号、相同内容的重放幂等返回原记录，不重复冻结；
  内容不同返回 `409 Conflict`；并发下同事件号由数据库唯一约束兜底。
- 复核事件号同样幂等：重复提交（含并发）只生效一次，不重复核销或归还；
  同一复核事件号作用于不同申报返回 `409`。

### 称重更正

- 已核销（已确认）申报发现称重错误时，创建一条**引用原申报事件号**的更正
  （`POST /api/corrections`），更正号全局唯一且创建幂等。**原申报记录与原台账永不覆盖**，
  重量演进通过申报版本链表达。
- 更正携带更正后的新重量，系统按与原已确认重量的差值判定方向并计算绝对差额：
  - **增重（INCREASE）**：创建时需**再次取得足够配额**——从可用量冻结差额
    （`CORRECTION_FREEZE` + `CORRECTION` 冻结明细），可用不足直接拒绝、不落任何记录；
    确认时冻结转核销（`frozen → consumed`，`CORRECTION_CONSUME`）。
  - **减重（DECREASE）**：创建时不碰余额；确认时**只归还实际差额**
    （`consumed → available`，`CORRECTION_REFUND`）。
- 确认 / 驳回使用独立复核事件号，幂等且只生效一次；驳回增重会释放冻结（`CORRECTION_RELEASE`），
  驳回减重仅终结更正、不动账。
- **版本校验拒绝旧决定**：创建更正时拍下原申报版本与账户版本；确认时任一版本已变化
  （例如账户上发生了转让接受、其它卸港确认或更正）即返回 `409`，旧决定被拒绝，
  必须基于最新重量重新发起。
- 整个差额处理在**单一事务**内完成：任何校验失败整体回滚，**不写入部分差额**，冻结明细不变。

### 并发与台账

- 所有余额变更先取账户行的**悲观写锁**（`SELECT ... FOR UPDATE`），转让单、申报单、更正单
  的状态迁移持有对应单据行锁；双向转让并发时按固定顺序加锁避免死锁。
- 转让接受、**取消 / 拒绝 / 到期释放**、卸港确认、更正确认并发时，单据行锁 + 账户行锁
  串行化状态迁移与余额变更，配合更正的版本校验，保证冻结量只结算或释放一次、任何时刻
  账户不得为负、不超转、不超捕、不重复核销或归还。
- 每次余额变化写入一条**不可变台账事件**（含变化后三余额快照及关联单号：转让 ID /
  卸港事件号 / 更正号）；失败请求事务回滚，不写入台账。
- 申报事件号、复核事件号、更正号、**转让各动作的外部请求号**均通过唯一约束 +
  幂等决策表实现重放与并发去重；幂等键带动作前缀，同一请求号不能跨动作或跨单据复用。

### 查询

- **账户差额台账**：`GET /api/quota-accounts/{id}/ledger`，可加 `?reference={单号}` 只看
  某笔转让 / 申报 / 更正的差额轨迹。
- **冻结明细**：`GET /api/quota-accounts/{id}/holds`，可加 `?activeOnly=true` 只看持有中明细。
- **可转余额分解**：`GET /api/quota-accounts/transfer-availability?season=&species=&holder=`
  （可选 `excludeTransferId`），区分可转、已上岸与被其他转让 / 卸港 / 更正占用的数量。
- **转让详情**：`GET /api/transfers/{id}/detail` 返回双方许可证转让前后余额、相关台账事件、
  冻结明细与单据状态 / 时间，用于核对双方余额与转让记录相互对应。
- **申报版本链**：`GET /api/landings/{eventId}/chain` 返回原始申报节点 + 按时间顺序的全部
  更正节点（含已驳回）、各自重量与版本，以及当前有效重量 `effectiveWeight`。
- 更正可按原申报查询：`GET /api/corrections?originalEventId=...`。

### 主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/quota-accounts` | 开立账户并核准入账 |
| GET | `/api/quota-accounts`、`/api/quota-accounts/{id}` | 账户查询（含版本号） |
| GET | `/api/quota-accounts/{id}/ledger` | 账户差额台账（可按 `reference` 过滤） |
| GET | `/api/quota-accounts/{id}/holds` | 冻结明细（`activeOnly` 只看持有中） |
| GET | `/api/quota-accounts/transfer-availability` | 可转余额分解（可转 / 已上岸 / 各类占用，可排除本笔转让） |
| POST | `/api/transfers` | 发起转让（`requestId` 幂等，冻结可转量） |
| POST | `/api/transfers/{id}/accept`、`/reject` | 接受 / 拒绝（请求体携带 `requestId`，幂等） |
| POST | `/api/transfers/{id}/cancel` | 转让方主动取消（`requestId` + `requesterHolder`，幂等） |
| POST | `/api/transfers/expire-due` | 处理到期转让 |
| GET | `/api/transfers/{id}`、`/api/transfers` | 转让查询 |
| GET | `/api/transfers/{id}/detail` | 转让详情：双方前后余额、台账、冻结明细与状态时间 |
| POST | `/api/landings` | 卸港申报，待复核并冻结（幂等） |
| POST | `/api/landings/{eventId}/confirm`、`/reject` | 港口复核确认 / 驳回（复核事件号幂等） |
| GET | `/api/landings/{eventId}`、`/api/landings` | 卸港记录查询 |
| GET | `/api/landings/{eventId}/chain` | 申报版本链与当前有效重量 |
| POST | `/api/corrections` | 创建称重更正（更正号幂等） |
| POST | `/api/corrections/{correctionId}/confirm`、`/reject` | 更正确认 / 驳回（版本校验、幂等） |
| GET | `/api/corrections/{correctionId}`、`/api/corrections?originalEventId=` | 更正查询 |

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
