# 配置与运行

最简单的方式是 Windows 下双击项目根目录的 `start.cmd`。成功后打开的账号页会显示实际端口和随机生成的演示账号。停止使用 `stop.cmd`。

## 数据放在哪里

默认位置为 `%LOCALAPPDATA%\SCENT\class-demo\<项目路径标识>`，窗口会显示完整路径。若 Windows 用户目录包含非 ASCII 字符，脚本改用公共目录中的用户专属子目录，并限制访问权限。

| 文件或目录 | 用途 |
| --- | --- |
| `config.json` | 本机工具路径、端口、账号及外部服务配置，含私密信息 |
| `SCENT-access.html` | 商城与后台入口、演示账号 |
| `data/` | 数据库、服务日志、进程记录 |
| `project/` | 独立运行副本与构建结果 |
| `tools/`、`downloads/` | 下载并校验的本地工具 |

停止服务不会删除数据。请勿上传运行目录，也不要在服务运行时移动或复制数据库文件。

## 支付宝与 AI

先停止服务，再修改运行目录中的 `config.json`。私钥和服务密钥保存为仓库外的文件，只填写绝对路径。再次启动后生效。

| 服务 | 配置字段 |
| --- | --- |
| 支付宝沙箱 | `alipayAppId`、`alipaySellerId`、`alipayPrivateKeyFile`、`alipayPublicKeyFile` |
| 网站与支付回调 | `publicOrigin`、`notifyOrigin` |
| 百炼香氛顾问 | `bailianBaseUrl`、`bailianAgentId`、`bailianEnvironmentId`、`bailianAgentVersion`、`bailianMcpServerId`、`bailianKeyFile` |
| 商品 MCP 鉴权 | `mcpTokenFile` |

配置模板见 [v1.example.json](v1.example.json)，后端支持的环境变量见 [application.properties](../apps/api/src/main/resources/application.properties)。

本地一键启动的地址仅本机可访问。接入支付宝回调或让云端百炼访问 MCP，需要自行准备可达的 HTTPS 地址及对应网关配置；`notifyOrigin` 应指向自己的回调来源，不能直接使用本机地址。对外暴露 MCP 时保留令牌校验，只开放所需路由。

## 自行准备环境

也可安装 Java 21、MySQL 8.4、Python 3.11+、项目要求的 Node.js/npm 和 Nginx，自行填写配置。复制 [v1.example.json](v1.example.json) 到仓库外的私有目录，填写工具路径、`runtimeDir` 和管理员密码。

以下命令在项目根目录执行；示例配置路径需替换为自己的绝对路径。`runtimeDir` 必须是源码之外的独立目录，路径不含中文。

```powershell
python deploy/v1.py build --config C:/SCENT-private/config.json
python deploy/v1.py web-install --config C:/SCENT-private/config.json
python deploy/v1.py web-build --config C:/SCENT-private/config.json
python deploy/v1.py init-db --config C:/SCENT-private/config.json
python deploy/v1.py start --config C:/SCENT-private/config.json
python deploy/v1.py preview-start --config C:/SCENT-private/config.json
python deploy/v1.py stop --config C:/SCENT-private/config.json
```

数据库只在首次初始化；后续运行无需重复执行 `init-db`。`mysqlPort`、`apiPort`、`previewPort` 可指定三个不同的本地端口，`publicOrigin` 和 `corsOrigins` 应与实际网页地址一致。一键脚本会自动选择空闲端口。

## 排查启动问题

- 下载失败：检查网络，保留已完成的下载后重新运行。工具校验失败时，按提示检查并移走对应的损坏缓存。
- 构建失败：查看运行目录 `data/logs/` 中的 Maven、npm 日志。
- 后端未就绪：查看 `data/logs/api.log` 与 MySQL 日志，不要清空已有数据库来绕过问题。
- 支付或 AI 未配置：填写自己的服务信息后重启；本地启动成功不代表第三方服务已接通。
