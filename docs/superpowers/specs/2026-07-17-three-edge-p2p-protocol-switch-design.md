# 三节点 P2P 边缘场景协议切换设计

## 1. 背景与目标

需要在本机 Mac 上构建三个 P2P 边缘节点：

- **Alice**：既可运行 BFIA 协议，也可运行隐语原生协议。SecretPad 前端可以在页面上切换协议；切换后由后端自动重启 Alice Kuscia 引擎到对应协议。
- **Bob**：仅运行 BFIA 协议，拥有完整的前后端、引擎、算子插件，可直接复用 Alice 的 BFIA 能力。
- **Kimi**：仅运行隐语原生协议，拥有标准的 SecretPad + Kuscia + Secretflow 环境，与 Alice 的隐语原生模式协同计算。

核心原则：**同一套 SecretPad/Kuscia 代码，通过配置决定节点角色、默认协议和是否允许切换**。

## 2. 角色与默认协议

| 节点 | 默认协议 | 是否可切换 | SecretPad 端口 | Kuscia 协议 |
| --- | --- | --- | --- | --- |
| Alice | 隐语原生 / BFIA（用户上次选择） | ✅ | 18080 | 随 Pad 切换 |
| Bob | BFIA | ❌ | 28090 | BFIA |
| Kimi | 隐语原生 | ❌ | 38090 | 隐语原生 |

## 3. Alice 页面切换 + 自动重启 Kuscia

### 3.1 前端现状

前端已使用 `localStorage.pageComputeIsConnectivity` 控制菜单显示：

- `false`：隐语原生菜单。
- `true`：BFIA 互联互通菜单。

切换入口位于顶部 Header 的下拉框。

### 3.2 新增后端配置接口

`GET /api/v1alpha1/config/protocol`

返回示例：

```json
{
  "defaultProtocol": "secretflow",
  "canSwitch": true
}
```

各节点返回值：

- Alice：`defaultProtocol=secretflow|bfia`（可配），`canSwitch=true`。
- Bob：`defaultProtocol=bfia`，`canSwitch=false`。
- Kimi：`defaultProtocol=secretflow`，`canSwitch=false`。

### 3.3 前端改造

1. `HeaderModel` 初始化时：
   - 优先读取 `localStorage` 中用户上次选择。
   - 若未设置，使用后端返回的 `defaultProtocol`。
2. 若 `canSwitch=false`，隐藏顶部协议下拉框。
3. Alice 切换协议时，弹出确认框，提供按钮“切换并重启 Kuscia 引擎”。
4. 点击按钮后调用后端重启接口，并显示重启进度/结果。

### 3.4 后端 Kuscia 重启接口

`POST /api/v1alpha1/node/kuscia/restart`

Body：

```json
{ "protocol": "bfia" }
```

权限：仅 Alice 节点可调用。

执行流程：

1. 校验当前节点为 Alice 且协议合法。
2. 调用本地脚本 `switch_alice_protocol.sh bfia|secretflow`。
3. 脚本内部：
   - `docker stop gaofeng-kuscia-autonomy-alice`。
   - 使用 `./kuscia.sh p2p -P bfia -a none` 或 `./kuscia.sh p2p -P kuscia -a none` 重启 Alice Kuscia。
   - 等待 Kuscia 健康（probe datamesh/gateway）。
   - 注册对应协议的 AppImage（BFIA 算子 / 隐语原生算子）。
4. 接口返回重启结果。

## 4. Bob：纯 BFIA 节点

- 复用 `NewProject/secretpad-bob` 目录作为独立 SecretPad 实例。
- 后端配置：
  - `secretpad.protocol.default=bfia`
  - `secretpad.protocol.canSwitch=false`
  - `secretpad.bfia.crossnode.enabled=true`
- 前端通过 `/api/v1alpha1/config/protocol` 得知不可切换，默认进入 BFIA 菜单。
- Kuscia：复用现有 `kuscia-node-bob` 或重新部署为 `gaofeng-kuscia-autonomy-bob`，固定以 BFIA 模式启动。
- 算子插件：复用 Alice BFIA 模式下的 `config/bfiacomponents/secretflow.json` 与对应 AppImage。
- 数据：使用 `secretpad-bob/db/` 下独立的 SQLite/H2 数据库。

## 5. Kimi：纯隐语原生节点

- 新建 `NewProject/secretpad-kimi` 目录，复制/软链 `target/secretpad.jar`、`config/`，并创建独立 `db/`。
- 后端配置：
  - `secretpad.protocol.default=secretflow`
  - `secretpad.protocol.canSwitch=false`
- 前端默认进入隐语原生菜单，隐藏协议切换入口。
- 部署新的 Kuscia autonomy 容器 `gaofeng-kuscia-autonomy-kimi`：

```bash
./kuscia.sh p2p -P kuscia -a none
```

- 注册标准 Secretflow AppImage。
- 数据：使用 `secretpad-kimi/db/` 下独立数据库。

## 6. 跨节点协同

### 6.1 Alice-BFIA ↔ Bob

- Alice 在 BFIA 模式下，SecretPad 通过 `/api/v1bfia/*` 创建项目/流程。
- Alice 与 Bob 的 SecretPad 通过 BFIA 跨节点同步 API 交换项目、审批、流程信息。
- 任务提交到 BFIA 模式的 Alice Kuscia，由 BFIA `interConnProtocol=bfia` 路由到 Bob Kuscia。

### 6.2 Alice-隐语 ↔ Kimi

- Alice 在隐语原生模式下，SecretPad 通过 `/api/v1alpha1/*` 创建原生项目，把 Kimi 添加为合作节点。
- 任务由原生模式的 Alice Kuscia 通过 `interConnProtocol=kuscia` 路由到 Kimi Kuscia。
- 双方 SecretPad 无需直接通信，Kuscia 负责任务调度。

## 7. Kuscia 网络与路由

- 三个节点均加入 Docker network `kuscia-exchange`。
- 端口分配避免冲突：
  - Alice：1080 / 80 / 8082 / 8083
  - Bob：1180 / 81 / 8085 / 8086
  - Kimi：1280 / 82 / 8087 / 8088
- Alice↔Bob 建立 `interConnProtocol=bfia` 的 DomainRoute/ClusterDomainRoute。
- Alice↔Kimi 建立 `interConnProtocol=kuscia` 的 ClusterDomainRoute。
- Alice Kuscia 切换协议后，路由配置保留在 k3s 中；建议切换脚本执行一次路由健康检查。

## 8. AppImage / 算子注册

| 节点/模式 | 注册 AppImage | 组件定义 |
| --- | --- | --- |
| Alice-BFIA | `ic-ecdh-psi`、`ss-lr` 等 BFIA AppImage | `config/bfiacomponents/secretflow.json` |
| Alice-隐语 | 标准 Secretflow AppImage | `config/components/secretflow.json` |
| Bob | 同 Alice-BFIA | 同 Alice-BFIA |
| Kimi | 标准 Secretflow AppImage | `config/components/secretflow.json` |

切换 Alice 协议后，必须重新注册当前协议所需 AppImage。

## 9. 数据隔离

- BFIA 数据：`bfia_project`、`bfia_graph`、`bfia_graph_node`、`bfia_job` 等表。
- 隐语原生数据：原有 `project`、`project_graph` 等表。
- 两套表共存于同一数据库文件，互不干扰。
- Alice、Bob、Kimi 各自使用独立数据库目录。

## 10. 部署脚本规划

| 脚本 | 作用 |
| --- | --- |
| `deploy_three_nodes.sh` | 一键部署 Alice/Bob/Kimi 三个节点（Kuscia + SecretPad 基础启动）。 |
| `switch_alice_protocol.sh <bfia|secretflow>` | 被后端 Kuscia 重启接口调用，负责停止并重启 Alice Kuscia、注册 AppImage。 |
| `start_secretpad_alice.sh` | 启动 Alice SecretPad（可切换模式）。 |
| `start_secretpad_bob.sh` | 启动 Bob SecretPad（固定 BFIA）。 |
| `start_secretpad_kimi.sh` | 启动 Kimi SecretPad（固定隐语原生）。 |

## 11. 改造清单

### 11.1 SecretPad 后端

- [ ] 新增 `GET /api/v1alpha1/config/protocol` 接口。
- [ ] 新增 `POST /api/v1alpha1/node/kuscia/restart` 接口（仅 Alice）。
- [ ] 在 `application.yaml` 中增加 `secretpad.protocol.default` 与 `secretpad.protocol.canSwitch` 配置。
- [ ] 根据节点角色加载不同默认协议配置。

### 11.2 SecretPad 前端

- [ ] `HeaderModel` 读取后端协议默认值与是否可切换。
- [ ] 不可切换时隐藏协议下拉框。
- [ ] 切换协议弹窗增加“重启 Kuscia 引擎”按钮并调用后端接口。

### 11.3 Kuscia 部署

- [ ] 部署 Kimi Kuscia autonomy 节点。
- [ ] 为 Bob/Kimi 注册对应 AppImage。
- [ ] 建立 Alice↔Bob、Alice↔Kimi 路由。

### 11.4 部署脚本与目录

- [ ] 复用 `NewProject/secretpad-bob`。
- [ ] 新建 `NewProject/secretpad-kimi`。
- [ ] 编写 `deploy_three_nodes.sh`、`switch_alice_protocol.sh`、`start_secretpad_*.sh`。

## 12. 风险与注意事项

1. **Kuscia 重启会中断任务**：Alice 切换协议前必须提示用户。
2. **AppImage 注册不持久**：切换协议后需要重新注册算子。
3. **路由协议不一致**：Alice 与 Bob 是 `bfia` 路由，与 Kimi 是 `kuscia` 路由；切换后需要健康检查。
4. **本机资源压力**：三个 autonomy Kuscia + 三个 SecretPad 同时运行，建议限制内存。
5. **Kuscia 数据卷保留**：重启 Alice Kuscia 时必须保留 containerd/k3s/证书卷，避免 nodeId 与路由失效。

## 13. 后续步骤

1. 编写实现计划（implementation plan）。
2. 按模块逐步改造：后端接口 → 前端交互 → Kuscia 部署脚本 → 联调验证。
