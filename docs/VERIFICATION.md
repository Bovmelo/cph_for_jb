# 逐功能验证矩阵

本项目把可脱离 IDE 的逻辑保持为纯 Kotlin，并在 JUnit 中验证；IDE 注册、UI 类加载和兼容性由 IntelliJ Platform Gradle 任务验证。

## 自动化验证

| 功能 | 验证方式 | 主要测试 |
| --- | --- | --- |
| Competitive Companion JSON | 解码完整载荷、忽略未来字段、转换领域模型 | `CompanionPayloadTest` |
| Loopback HTTP | 在随机 loopback 端口启动真实服务，发送 POST、OPTIONS、非法方法、非法 JSON 和超限请求 | `CompanionHttpServerTest` |
| 文件命名 | Codeforces、AtCoder、Luogu、Kattis、Unicode、Java 类名和保留文件名 | `ProblemNamingTest` |
| 模板 | 全语言包含光标占位符，变量替换，Python 和 Java 特殊规则 | `TemplateEngineTest` |
| `.cph` 持久化 | 兼容路径、原子往返、损坏文件不被改写 | `ProblemStoreTest` |
| 用例 JSON | 文件往返、重新生成 ID、根类型/字段/空数组校验 | `TestCaseExchangeTest` |
| 安全参数解析 | 引号、空参数、转义、未闭合输入；全程不使用 shell | `CommandArgumentsTest` |
| 语言命令 | 每种扩展均有 adapter，Kotlin/Java/C#/ONLINE_JUDGE/-O2/sanitizer 参数结构 | `LanguageAdapterTest` |
| 真实语言工具链 | 对本机已安装的每个编译器现场编译并运行最小解答；未安装项明确跳过 | `LanguageExecutionIntegrationTest` |
| 真实进程执行 | 现场编译 Java fixture，验证 stdin/stdout、退出码、stderr、超时、启动失败和 4 MiB 输出上限 | `ProcessRunnerTest` |
| 判题 | Accepted、Wrong Answer、TLE、Runtime Error、Output Limit、编译、顺序执行和取消 | `JudgeEngineTest` |
| 自定义 checker | 验证临时文件内容、退出码语义、诊断输出和清理 | `JudgeEngineTest` |
| 设置持久化 | IntelliJ XML 序列化后工具链映射与布尔项保持 | `CphSettingsSerializationTest` |
| 原版本地启动语义 | 首次命令仅创建 `Local: 文件名` 和空白用例，后续命令才运行；未加载题目时按钮仍可用 | `LocalProblemFactoryTest`, `RunTestCasesPolicyTest`, `CphUiActionPolicyTest` |
| 文档保存调度 | 保存与后续运行在同一个写意图调度中依次执行；平台测试确认 `saveAllDocuments()` 获得写意图访问 | `DocumentSaveCoordinatorTest`, `DocumentSaveCoordinatorPlatformTest` |

运行全部自动化验证：

```shell
./gradlew test
```

测试报告位于 `build/reports/tests/test/index.html`。`LanguageExecutionIntegrationTest` 使用 JUnit assumption：CI 或本机没有安装的外部语言工具链会显示为 skipped，不会伪装成已验证。

工具链不在 `PATH` 时，可用测试专用环境变量指定可执行文件，例如：

```shell
CPH_TEST_KOTLIN_COMPILER=/path/to/kotlinc ./gradlew test --tests '*LanguageExecutionIntegrationTest'
```

## IDE 与发布验证

```shell
./gradlew buildSearchableOptions
./gradlew buildPlugin
./gradlew verifyPlugin
```

- `buildSearchableOptions`：启动无头 IDE，加载插件与设置页面。
- `buildPlugin`：完成代码插桩、描述符处理、沙箱组装和发布 ZIP。
- `verifyPlugin`：使用 JetBrains Plugin Verifier 检查目标 IDE、API 兼容性和动态加载资格。

## 开发 IDE 手工冒烟

```shell
./gradlew runIde --args=/absolute/path/to/a/test-project
```

开发实例会自动关闭许可确认、项目信任和启动提示等阻塞对话框。先确认 VS Code、Cursor 或其他竞赛工具没有同时占用 CPH 端口；浏览器扩展使用 `localhost:27121`，并发监听可能让题目进入错误的编辑器。

IDE 打开后，可先在另一个终端直接验证真实 Companion 通路：

```shell
curl --request POST http://127.0.0.1:27121/ \
  --header 'Content-Type: application/json' \
  --data-binary '{"name":"IDE Smoke Sum","url":"https://codeforces.com/contest/999/problem/A","timeLimit":2000,"memoryLimit":256,"tests":[{"input":"2 3\n","output":"5\n"}]}'
```

期望收到 `{}`，项目中出现 `999A.kt` 及对应 `.cph` 元数据，且工具窗口展示同一组样例。随后继续以下验收：

1. 打开一个临时项目和 Kotlin 文件，按 `Ctrl+Alt+B`，确认只创建本地题目和一个空白用例，不立即编译。
2. 填写两个用例后立即按 `Ctrl+Alt+B` 或点 **Run Testcases**，并分别运行单例，确认没有 Internal Error，且最新输入参与 verdict、耗时和 diff。
3. 启动长时间程序后点 **Stop**，确认当前及剩余用例变为 Cancelled。
4. 从 Competitive Companion 发送题目，确认源码、模板光标、元数据和样例同时出现。
5. 导出 JSON，清空后重新导入，确认内容恢复且 ID 不冲突。
6. 启用一个退出码为 0/非 0 的 Python checker，确认 Expected output 隐藏且结果随退出码变化。
