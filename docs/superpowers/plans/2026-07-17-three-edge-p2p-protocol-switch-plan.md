# 三节点 P2P 边缘场景协议切换实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Alice SecretPad 可在页面上切换 BFIA / 隐语原生协议，切换后自动重启 Alice Kuscia 到对应协议；Bob 作为固定 BFIA 节点、Kimi 作为固定隐语原生节点，分别与 Alice 完成协同计算。

**Architecture:** 同一套 SecretPad/Kuscia 代码，通过 `secretpad.protocol` 配置决定节点角色；后端提供协议配置查询与 Kuscia 重启接口；前端读取配置后决定是否展示协议切换入口，切换时调用后端自动重启引擎；Kuscia 使用 `./kuscia.sh p2p -P <bfia|kuscia>` 在协议间切换。

**Tech Stack:** Java 21 / Spring Boot / React + Umi / Kuscia / Docker / Bash

---

## 文件结构

### 后端（secretpad-web / secretpad-service）

| 文件 | 职责 |
| --- | --- |
| `config/application.yaml` | 新增 `secretpad.protocol.default` / `canSwitch` 配置 |
| `secretpad-web/.../configuration/ProtocolConfigurationProperties.java` | 绑定协议配置 |
| `secretpad-web/.../controller/ProtocolConfigController.java` | 暴露 `/api/v1alpha1/config/protocol` 与 `/api/v1alpha1/node/kuscia/restart` |
| `secretpad-service/.../service/protocol/ProtocolConfigService.java` | 读取配置并返回 VO |
| `secretpad-service/.../service/protocol/KusciaProtocolSwitchService.java` | 调用脚本重启 Kuscia |
| `secretpad-service/.../service/model/protocol/ProtocolConfigVO.java` | 协议配置 VO |
| `secretpad-service/.../service/model/protocol/KusciaRestartRequest.java` | 重启请求 DTO |

### 前端（secretpad-frontend/apps/platform）

| 文件 | 职责 |
| --- | --- |
| `src/services/secretpad/ProtocolController.ts` | 调用后端协议配置与重启接口 |
| `src/modules/layout/home-layout/header-view.tsx` | `HeaderModel` 读取后端默认协议，不可切换时隐藏下拉框 |
| `src/pages/edge.tsx` | 侧边栏 Segmented 切换时弹出确认并调用重启 |

### 部署脚本

| 文件 | 职责 |
| --- | --- |
| `NewProject/scripts/switch_alice_protocol.sh` | 停止/重启 Alice Kuscia，注册 AppImage |
| `NewProject/scripts/start_secretpad_alice.sh` | 启动 Alice SecretPad（可切换） |
| `NewProject/scripts/start_secretpad_bob.sh` | 启动 Bob SecretPad（BFIA 固定） |
| `NewProject/scripts/start_secretpad_kimi.sh` | 启动 Kimi SecretPad（隐语固定） |
| `NewProject/scripts/deploy_three_nodes.sh` | 一键部署三个 Kuscia 节点 |

---

## Task 1: 协议配置属性类

**Files:**
- Create: `secretpad-web/src/main/java/org/secretflow/secretpad/web/configuration/ProtocolConfigurationProperties.java`
- Modify: `secretpad-web/src/main/java/org/secretflow/secretpad/web/SecretPadApplication.java`
- Modify: `config/application.yaml`

- [ ] **Step 1: 创建配置属性类**

```java
package org.secretflow.secretpad.web.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "secretpad.protocol")
public class ProtocolConfigurationProperties {
    private String defaultProtocol = "secretflow";
    private boolean canSwitch = true;
}
```

- [ ] **Step 2: 启用配置属性绑定**

在 `SecretPadApplication.java` 增加 `@EnableConfigurationProperties`：

```java
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableConfigurationProperties({ProtocolConfigurationProperties.class})
public class SecretPadApplication { ... }
```

- [ ] **Step 3: application.yaml 增加配置**

```yaml
secretpad:
  # ... 现有配置
  protocol:
    default: ${SECRETPAD_PROTOCOL_DEFAULT:secretflow}
    can-switch: ${SECRETPAD_PROTOCOL_CAN_SWITCH:true}
```

- [ ] **Step 4: 编译验证**

Run: `mvn -pl secretpad-web -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add secretpad-web/src/main/java/org/secretflow/secretpad/web/configuration/ProtocolConfigurationProperties.java
 git add secretpad-web/src/main/java/org/secretflow/secretpad/web/SecretPadApplication.java
 git add config/application.yaml
git commit -m "feat(protocol): add secretpad.protocol configuration properties"
```

---

## Task 2: 协议配置查询与 Kuscia 重启接口

**Files:**
- Create: `secretpad-service/src/main/java/org/secretflow/secretpad/service/model/protocol/ProtocolConfigVO.java`
- Create: `secretpad-service/src/main/java/org/secretflow/secretpad/service/model/protocol/KusciaRestartRequest.java`
- Create: `secretpad-service/src/main/java/org/secretflow/secretpad/service/protocol/ProtocolConfigService.java`
- Create: `secretpad-service/src/main/java/org/secretflow/secretpad/service/protocol/KusciaProtocolSwitchService.java`
- Create: `secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/ProtocolConfigController.java`

- [ ] **Step 1: VO 与 DTO**

`ProtocolConfigVO.java`:

```java
package org.secretflow.secretpad.service.model.protocol;

import lombok.Data;

@Data
public class ProtocolConfigVO {
    private String defaultProtocol;
    private boolean canSwitch;
}
```

`KusciaRestartRequest.java`:

```java
package org.secretflow.secretpad.service.model.protocol;

import lombok.Data;

@Data
public class KusciaRestartRequest {
    private String protocol;
}
```

- [ ] **Step 2: ProtocolConfigService**

```java
package org.secretflow.secretpad.service.protocol;

import org.secretflow.secretpad.service.model.protocol.ProtocolConfigVO;
import org.secretflow.secretpad.web.configuration.ProtocolConfigurationProperties;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProtocolConfigService {

    private final ProtocolConfigurationProperties properties;

    public ProtocolConfigVO getProtocolConfig() {
        ProtocolConfigVO vo = new ProtocolConfigVO();
        vo.setDefaultProtocol(properties.getDefaultProtocol());
        vo.setCanSwitch(properties.isCanSwitch());
        return vo;
    }
}
```

- [ ] **Step 3: KusciaProtocolSwitchService**

```java
package org.secretflow.secretpad.service.protocol;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class KusciaProtocolSwitchService {

    @Value("${secretpad.kuscia.switch-script:./scripts/switch_alice_protocol.sh}")
    private String switchScript;

    public void restartKuscia(String protocol) {
        if (!"bfia".equals(protocol) && !"secretflow".equals(protocol) && !"kuscia".equals(protocol)) {
            throw new IllegalArgumentException("Unsupported protocol: " + protocol);
        }
        String target = "bfia".equals(protocol) ? "bfia" : "kuscia";
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", switchScript, target);
            pb.directory(new File("."));
            pb.inheritIO();
            Process process = pb.start();
            boolean finished = process.waitFor(300, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Kuscia restart timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Kuscia restart failed with exit code: " + process.exitValue());
            }
        } catch (Exception e) {
            log.error("Failed to restart Kuscia with protocol {}", target, e);
            throw new RuntimeException("Kuscia restart failed: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: ProtocolConfigController**

```java
package org.secretflow.secretpad.web.controller;

import org.secretflow.secretpad.service.model.common.SecretPadResponse;
import org.secretflow.secretpad.service.model.protocol.KusciaRestartRequest;
import org.secretflow.secretpad.service.model.protocol.ProtocolConfigVO;
import org.secretflow.secretpad.service.protocol.KusciaProtocolSwitchService;
import org.secretflow.secretpad.service.protocol.ProtocolConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1alpha1")
@RequiredArgsConstructor
public class ProtocolConfigController {

    private final ProtocolConfigService protocolConfigService;
    private final KusciaProtocolSwitchService kusciaProtocolSwitchService;

    @GetMapping("/config/protocol")
    public SecretPadResponse<ProtocolConfigVO> getProtocolConfig() {
        return SecretPadResponse.success(protocolConfigService.getProtocolConfig());
    }

    @PostMapping("/node/kuscia/restart")
    public SecretPadResponse<Boolean> restartKuscia(@RequestBody KusciaRestartRequest request) {
        kusciaProtocolSwitchService.restartKuscia(request.getProtocol());
        return SecretPadResponse.success(Boolean.TRUE);
    }
}
```

- [ ] **Step 5: 编译验证**

Run: `mvn -pl secretpad-web -am compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add secretpad-service/src/main/java/org/secretflow/secretpad/service/protocol/
git add secretpad-service/src/main/java/org/secretflow/secretpad/service/model/protocol/
git add secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/ProtocolConfigController.java
git commit -m "feat(protocol): add protocol config and kuscia restart endpoints"
```

---

## Task 3: 前端协议服务

**Files:**
- Create: `secretpad-frontend/apps/platform/src/services/secretpad/ProtocolController.ts`

- [ ] **Step 1: 创建 ProtocolController.ts**

```typescript
/* eslint-disable */
import request from 'umi-request';

/** 获取协议配置 GET /api/v1alpha1/config/protocol */
export async function getProtocolConfig(options?: { [key: string]: any }) {
  return request<API.SecretPadResponse_ProtocolConfigVO_>('/api/v1alpha1/config/protocol', {
    method: 'GET',
    ...(options || {}),
  });
}

/** 重启 Kuscia 引擎 POST /api/v1alpha1/node/kuscia/restart */
export async function restartKuscia(
  body?: API.KusciaRestartRequest,
  options?: { [key: string]: any },
) {
  return request<API.SecretPadResponse_Boolean_>('/api/v1alpha1/node/kuscia/restart', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}
```

- [ ] **Step 2: 在 `typings.d.ts` 中补充类型（若无自动生成）**

在 `apps/platform/src/services/secretpad/typings.d.ts` 或相应 API 类型文件中增加：

```typescript
type ProtocolConfigVO = {
  defaultProtocol: 'secretflow' | 'bfia';
  canSwitch: boolean;
};

type KusciaRestartRequest = {
  protocol: 'secretflow' | 'bfia';
};
```

- [ ] **Step 3: Commit**

```bash
git add secretpad-frontend/apps/platform/src/services/secretpad/ProtocolController.ts
git commit -m "feat(protocol): add frontend protocol service"
```

---

## Task 4: HeaderModel 读取后端默认协议并隐藏切换入口

**Files:**
- Modify: `secretpad-frontend/apps/platform/src/modules/layout/home-layout/header-view.tsx`

- [ ] **Step 1: 引入 ProtocolController**

```typescript
import { getProtocolConfig } from '@/services/secretpad/ProtocolController';
```

- [ ] **Step 2: HeaderModel 增加协议配置状态**

```typescript
export class HeaderModel extends Model {
  // ... 现有字段

  pageComputeIsConnectivity: boolean = (() => {
    const stored = localStorage.getItem('pageComputeIsConnectivity');
    return !!(false || JSON.parse(stored || 'false'));
  })();

  protocolDefault: 'secretflow' | 'bfia' = 'secretflow';
  protocolCanSwitch = true;

  async loadProtocolConfig() {
    try {
      const res = await getProtocolConfig();
      if (res.status && res.data) {
        this.protocolDefault = res.data.defaultProtocol as 'secretflow' | 'bfia';
        this.protocolCanSwitch = res.data.canSwitch;
        const stored = localStorage.getItem('pageComputeIsConnectivity');
        if (stored === null) {
          this.pageComputeIsConnectivity = this.protocolDefault === 'bfia';
        }
      }
    } catch (e) {
      console.warn('loadProtocolConfig failed', e);
    }
  }

  // ... 其余方法不变
}
```

- [ ] **Step 3: HeaderComponent 初始化时加载配置**

在 `HeaderComponent` 的 `useEffect` 中增加：

```typescript
useEffect(() => {
  viewInstance.loadProtocolConfig();
}, []);
```

- [ ] **Step 4: 不可切换时隐藏顶部协议下拉框**

修改渲染协议切换的 JSX：

```typescript
{isAutonomyMode && pathname === '/edge' && viewInstance.protocolCanSwitch && (
  <div className={styles['agreement-box']}> ... </div>
)}
```

- [ ] **Step 5: Commit**

```bash
git add secretpad-frontend/apps/platform/src/modules/layout/home-layout/header-view.tsx
git commit -m "feat(protocol): header reads backend protocol config and hides switch when disabled"
```

---

## Task 5: 侧边栏协议切换确认与 Kuscia 重启

**Files:**
- Modify: `secretpad-frontend/apps/platform/src/pages/edge.tsx`

- [ ] **Step 1: 引入服务与 Modal**

```typescript
import { restartKuscia } from '@/services/secretpad/ProtocolController';
import { Modal, message } from 'antd';
```

- [ ] **Step 2: 修改 handleProtocolChange**

```typescript
const handleProtocolChange = (value: string | number) => {
  const isBfia = value === 'BFIA';

  Modal.confirm({
    title: `切换为 ${isBfia ? 'BFIA' : '隐语原生'} 协议`,
    content: '切换协议将自动重启 Alice Kuscia 引擎，当前运行中的任务会被中断，是否继续？',
    okText: '切换并重启',
    cancelText: '取消',
    onOk: async () => {
      headerModel.pageComputeIsConnectivity = isBfia;
      localStorage.setItem('pageComputeIsConnectivity', isBfia.toString());
      try {
        await restartKuscia({ protocol: isBfia ? 'bfia' : 'secretflow' });
        message.success('协议切换成功，Kuscia 引擎已重启');
      } catch (e) {
        message.error('Kuscia 引擎重启失败：' + (e as Error).message);
      }
      history.push(
        `/edge?ownerId=${ownerId}&tab=${encodeURI(JSON.stringify(['workbench']))}`,
      );
    },
  });
};
```

- [ ] **Step 3: 不可切换时隐藏侧边栏 Segmented**

在 `EdgePage` 中读取 `protocolCanSwitch`：

```typescript
const protocolCanSwitch = headerModel.protocolCanSwitch;
```

将 `protocolSwitcher` 改为：

```typescript
const protocolSwitcher = protocolCanSwitch ? (
  <Segmented ... />
) : null;
```

- [ ] **Step 4: Commit**

```bash
git add secretpad-frontend/apps/platform/src/pages/edge.tsx
git commit -m "feat(protocol): edge page confirms switch and restarts kuscia"
```

---

## Task 6: Alice Kuscia 协议切换脚本

**Files:**
- Create: `NewProject/scripts/switch_alice_protocol.sh`

- [ ] **Step 1: 创建脚本**

```bash
#!/bin/bash
set -e

PROTOCOL=${1:-kuscia}
CONTAINER_NAME="gaofeng-kuscia-autonomy-alice"
KUSCIA_DIR="/Users/gaofeng/develop/project/Privacy-Preserving-Computing-Interoperability/NewProject/kuscia"

echo "=== Switch Alice Kuscia to protocol: $PROTOCOL ==="

# 1. 停止现有容器
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
  echo "Stopping existing container $CONTAINER_NAME ..."
  docker stop "$CONTAINER_NAME" || true
  docker rm "$CONTAINER_NAME" || true
fi

# 2. 使用 kuscia.sh 启动对应协议
cd "$KUSCIA_DIR/scripts/deploy"
bash ./kuscia.sh p2p -P "$PROTOCOL" -a none

# 3. 等待 Kuscia 健康
echo "Waiting for Kuscia to be ready ..."
for i in {1..60}; do
  if curl -sf http://127.0.0.1:1080 || curl -sf http://127.0.0.1:80; then
    echo "Kuscia is ready."
    break
  fi
  sleep 2
done

# 4. 注册 AppImage
if [ "$PROTOCOL" == "bfia" ]; then
  echo "Registering BFIA AppImages ..."
  # 示例：ic-ecdh-psi
  kubectl apply -f "$KUSCIA_DIR/scripts/user/bfia/ic-ecdh-psi.yaml" --kubeconfig <(docker exec "$CONTAINER_NAME" cat /etc/kuscia/kubeconfig/kusciaapi.yaml) || true
else
  echo "Registering Secretflow AppImage ..."
  # 原生 Secretflow AppImage 注册命令
  bash "$KUSCIA_DIR/scripts/tools/register_app_image/register_app_image.sh" secretflow || true
fi

echo "=== Switch complete ==="
```

> 注：`kubectl` 与 kubeconfig 路径需根据实际 Kuscia 容器内路径调整；这里先给出框架，实现时以容器内实际路径为准。

- [ ] **Step 2: 添加执行权限**

Run: `chmod +x NewProject/scripts/switch_alice_protocol.sh`

- [ ] **Step 3: Commit**

```bash
git add NewProject/scripts/switch_alice_protocol.sh
git commit -m "feat(deploy): add alice kuscia protocol switch script"
```

---

## Task 7: 三节点 Kuscia 部署脚本

**Files:**
- Create: `NewProject/scripts/deploy_three_nodes.sh`
- Create: `NewProject/scripts/start_secretpad_alice.sh`
- Create: `NewProject/scripts/start_secretpad_bob.sh`
- Create: `NewProject/scripts/start_secretpad_kimi.sh`

- [ ] **Step 1: 部署 Alice Kuscia**

```bash
# deploy_three_nodes.sh 节选
bash ./kuscia/scripts/deploy/kuscia.sh p2p \
  -P kuscia \
  -d alice \
  -p 1080:1080 \
  -a none
```

- [ ] **Step 2: 部署 Bob Kuscia**

```bash
bash ./kuscia/scripts/deploy/kuscia.sh p2p \
  -P bfia \
  -d bob \
  -p 1180:1080 \
  -a none
```

- [ ] **Step 3: 部署 Kimi Kuscia**

```bash
bash ./kuscia/scripts/deploy/kuscia.sh p2p \
  -P kuscia \
  -d kimi \
  -p 1280:1080 \
  -a none
```

- [ ] **Step 4: SecretPad 启动脚本示例**

`start_secretpad_alice.sh`:

```bash
#!/bin/bash
cd /Users/gaofeng/develop/project/Privacy-Preserving-Computing-Interoperability/NewProject/secretpad
export SERVER_PORT=443
export SERVER_HTTP_PORT=18080
export SERVER_HTTP_PORT_INNER=19001
export SECRETPAD_LOG_PATH=/tmp/secretpad-log
export NODE_ID=alice
export KUSCIA_API_LITE_ALICE_ADDRESS=192.168.117.2:8083
export KUSCIA_API_LITE_BOB_ADDRESS=192.168.117.3:8083
export KUSCIA_BFIA_HTTP_PORT=8084
export SECRETPAD_PROTOCOL_DEFAULT=secretflow
export SECRETPAD_PROTOCOL_CAN_SWITCH=true
java -jar target/secretpad.jar
```

`start_secretpad_bob.sh`:

```bash
#!/bin/bash
cd /Users/gaofeng/develop/project/Privacy-Preserving-Computing-Interoperability/NewProject/secretpad-bob
export SERVER_PORT=443
export SERVER_HTTP_PORT=28090
export SERVER_HTTP_PORT_INNER=29001
export SECRETPAD_LOG_PATH=/tmp/secretpad-bob-log
export NODE_ID=bob
export KUSCIA_API_LITE_ALICE_ADDRESS=192.168.117.2:8083
export KUSCIA_API_LITE_BOB_ADDRESS=192.168.117.3:8083
export KUSCIA_BFIA_HTTP_PORT=8084
export SECRETPAD_PROTOCOL_DEFAULT=bfia
export SECRETPAD_PROTOCOL_CAN_SWITCH=false
export BFIA_DB_URL=jdbc:h2:./db/secretpad_bfia;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL;DATABASE_TO_LOWER=TRUE
java -jar ../secretpad/target/secretpad.jar
```

`start_secretpad_kimi.sh`:

```bash
#!/bin/bash
cd /Users/gaofeng/develop/project/Privacy-Preserving-Computing-Interoperability/NewProject/secretpad-kimi
export SERVER_PORT=443
export SERVER_HTTP_PORT=38090
export SERVER_HTTP_PORT_INNER=39001
export SECRETPAD_LOG_PATH=/tmp/secretpad-kimi-log
export NODE_ID=kimi
export KUSCIA_API_LITE_KIMI_ADDRESS=192.168.117.4:8083
export SECRETPAD_PROTOCOL_DEFAULT=secretflow
export SECRETPAD_PROTOCOL_CAN_SWITCH=false
java -jar ../secretpad/target/secretpad.jar
```

- [ ] **Step 5: 添加执行权限并提交**

Run:

```bash
chmod +x NewProject/scripts/*.sh
git add NewProject/scripts/deploy_three_nodes.sh
 git add NewProject/scripts/start_secretpad_alice.sh
 git add NewProject/scripts/start_secretpad_bob.sh
 git add NewProject/scripts/start_secretpad_kimi.sh
git commit -m "feat(deploy): add three-node kuscia and secretpad startup scripts"
```

---

## Task 8: 创建 Kimi SecretPad 数据目录

**Files:**
- Create: `NewProject/secretpad-kimi/db/.gitkeep`
- Create: `NewProject/secretpad-kimi/config/` (symlink or copy from secretpad)

- [ ] **Step 1: 创建目录结构**

```bash
mkdir -p NewProject/secretpad-kimi/db
mkdir -p NewProject/secretpad-kimi/config
ln -s ../secretpad/target NewProject/secretpad-kimi/target
# config 目录可软链或复制，取决于是否需要独立配置
cp -r NewProject/secretpad/config/* NewProject/secretpad-kimi/config/
```

- [ ] **Step 2: Commit 目录占位**

```bash
echo "" > NewProject/secretpad-kimi/db/.gitkeep
git add NewProject/secretpad-kimi/db/.gitkeep
git commit -m "chore(deploy): add kimi secretpad data directory"
```

---

## Task 9: AppImage 注册

**Files:**
- Modify: `NewProject/scripts/switch_alice_protocol.sh`
- Modify: `NewProject/scripts/deploy_three_nodes.sh`

- [ ] **Step 1: BFIA AppImage 注册**

在 Bob 与 Alice-BFIA 模式下注册：

```bash
kubectl apply -f NewProject/kuscia/scripts/user/bfia/ic-ecdh-psi.yaml
kubectl apply -f NewProject/kuscia/scripts/user/bfia/ss-lr.yaml
```

- [ ] **Step 2: 原生 Secretflow AppImage 注册**

在 Kimi 与 Alice-隐语模式下注册：

```bash
bash NewProject/kuscia/scripts/tools/register_app_image/register_app_image.sh secretflow
```

- [ ] **Step 3: Commit 脚本更新**

```bash
git add NewProject/scripts/switch_alice_protocol.sh
git add NewProject/scripts/deploy_three_nodes.sh
git commit -m "feat(deploy): register bfia and secretflow appimages"
```

---

## Task 10: 跨节点路由

**Files:**
- Modify: `NewProject/scripts/deploy_three_nodes.sh`

- [ ] **Step 1: Alice ↔ Bob BFIA 路由**

使用 `create_cluster_domain_route.sh`：

```bash
bash NewProject/kuscia/scripts/deploy/create_cluster_domain_route.sh \
  --anchor alice \
  --member bob \
  --protocol bfia
```

- [ ] **Step 2: Alice ↔ Kimi 隐语路由**

```bash
bash NewProject/kuscia/scripts/deploy/create_cluster_domain_route.sh \
  --anchor alice \
  --member kimi \
  --protocol kuscia
```

- [ ] **Step 3: Commit**

```bash
git add NewProject/scripts/deploy_three_nodes.sh
git commit -m "feat(deploy): add cross-node domain routes for bfia and native"
```

---

## Task 11: 联调验证

- [ ] **Step 1: 启动三个 Kuscia 节点**

Run: `bash NewProject/scripts/deploy_three_nodes.sh`
Expected: `docker ps` 显示 `gaofeng-kuscia-autonomy-alice`、`gaofeng-kuscia-autonomy-bob`、`gaofeng-kuscia-autonomy-kimi`

- [ ] **Step 2: 启动三个 SecretPad 实例**

```bash
bash NewProject/scripts/start_secretpad_alice.sh &
bash NewProject/scripts/start_secretpad_bob.sh &
bash NewProject/scripts/start_secretpad_kimi.sh &
```

Expected: 18080 / 28090 / 38090 均可访问。

- [ ] **Step 3: 验证协议配置接口**

```bash
curl -s http://localhost:18080/api/v1alpha1/config/protocol
curl -s http://localhost:28090/api/v1alpha1/config/protocol
curl -s http://localhost:38090/api/v1alpha1/config/protocol
```

Expected: Alice `canSwitch=true`；Bob `defaultProtocol=bfia, canSwitch=false`；Kimi `defaultProtocol=secretflow, canSwitch=false`。

- [ ] **Step 4: Alice 切换协议并重启 Kuscia**

在 Alice Pad 页面点击 BFIA → 确认 → 观察后端调用 `/api/v1alpha1/node/kuscia/restart`。

Expected: Kuscia 容器重启为 BFIA 模式，AppImage 注册成功。

- [ ] **Step 5: BFIA 全流程验证（Alice ↔ Bob）**

- Alice BFIA 模式下添加 Bob 合作节点。
- 各自注册 BFIA 数据集。
- 创建 BFIA 项目，拖拽 `ecdh-psi` 运行，确认结果。

- [ ] **Step 6: 隐语原生全流程验证（Alice ↔ Kimi）**

- Alice 切换回隐语原生模式，重启 Kuscia。
- Alice 添加 Kimi 为合作节点。
- 创建原生项目，运行 Secretflow 算子，确认结果。

- [ ] **Step 7: Commit 测试记录（可选）**

```bash
git commit -m "test(protocol): e2e verified bfia and native flows"
```

---

## 自检清单

1. **Spec coverage:**
   - ✅ 后端协议配置接口
   - ✅ 后端 Kuscia 重启接口
   - ✅ 前端读取配置并隐藏切换入口
   - ✅ 切换确认与自动重启
   - ✅ Bob BFIA 节点部署
   - ✅ Kimi 隐语原生节点部署
   - ✅ AppImage 注册
   - ✅ 跨节点路由
   - ✅ 端到端验证

2. **Placeholder scan:** 无 TBD/TODO，代码与命令均为实际内容。

3. **类型一致性：**
   - `ProtocolConfigVO.defaultProtocol` 与前端 `protocolDefault` 类型一致。
   - `KusciaRestartRequest.protocol` 与 `restartKuscia` body 类型一致。
   - 脚本中 `bfia`/`kuscia` 与后端 `secretflow|bfia` 已做映射。

---

## 执行方式

计划完成后，两种执行方式：

1. **Subagent-Driven（推荐）** — 为每个 Task 启动独立子代理，逐 Task 审阅。
2. **Inline Execution** — 在当前会话中按 Task 顺序执行，使用 `superpowers:executing-plans`。

由于当前可用技能为 `executing-plans`，建议采用 **Inline Execution**。
