# CPH for JetBrains

CPH for JetBrains 是使用 Kotlin 编写的 IntelliJ Platform 插件。它以 JetBrains 原生工具窗口复现 [Competitive Programming Helper](https://marketplace.visualstudio.com/items?itemName=DivyanshuAgrawal.competitive-programming-helper) 的竞赛工作流，并兼容 Competitive Companion 协议。

Kotlin 是默认语言和一等支持语言。插件只依赖 IntelliJ Platform，不依赖某个产品的语言插件；编译和运行均通过参数数组调用本机工具链，因此可用于 IntelliJ IDEA、CLion、PyCharm 等基于 2025.2 或更新平台的产品。

## 功能

- 在 `127.0.0.1:27121` 接收 Competitive Companion 题目，创建源码、写入模板并自动打开工具窗口。
- 支持 Kotlin、C++、C、C#、Rust、Go、Haskell、Python、Ruby、Java、JavaScript 和 Cangjie。
- 为本地源码创建题目，添加、编辑、删除、清空、导入、导出和单独运行测试用例。
- 将题目元数据保存为兼容 CPH 的 `.cph/.<source>_<md5>.prob` 文件。
- 每次运行只编译一次，随后顺序执行用例；支持编译错误、运行错误、stderr 策略、超时、输出上限和取消。
- 按 CPH 规则忽略每行首尾空白，并展示逐行输出差异。
- 每个用例同时显示耗时和峰值内存（Linux 读取 `VmHWM`，macOS 读取 `proc_pid_rusage` 的峰值物理内存，Windows 读取 `PeakWorkingSetSize`；读取失败时只显示耗时）。注意 JVM 语言的内存包含 JVM 自身的基础开销。 耗时只统计程序本身的运行，不含编译；macOS 首次启动新编译的可执行文件时会有几百毫秒的系统检查，插件会在计时开始前先让这次检查完成（不会执行你的程序），所以它不会算进第一个用例。
- 判题结果为 Wrong Answer 时可点击 **Set Expected**，把本次输出直接设为预期输出。
- 用例的 Input 可以改为选择一个文件：界面只显示文件路径，运行时以流的方式交给程序的 stdin，不读入内存，也没有大小限制。使用输入文件的用例不能导出为 JSON。
- 每个用例可单独启用/禁用：禁用的用例会被 Run Testcases 跳过，但仍可单独运行。
- 对拍（Stress Test）：选择生成器和暴力解，自动循环比对；发现差异后，把触发用例（输入 + 暴力解输出）加入用例列表。
- 一键加题（Add problem）：输入题号后按当前 IDE 创建对应语言的空源码文件并加载为本地题目（不写入内置模板；只有在设置里为该语言配置了模板文件时才使用它）。CLion 创建 `<题号>.cpp`，在项目根目录的 `CMakeLists.txt` 末尾追加 `add_executable`，并请求重新加载 CMake、选中同名的运行配置；IntelliJ IDEA 可选 Kotlin 或 Java，PyCharm 创建 Python，GoLand、RustRover、RubyMine、WebStorm、Rider 分别创建 Go、Rust、Ruby、JavaScript、C#，其他 IDE 使用设置里的默认语言。
- 支持 Python 自定义 checker（Special Judge）。checker 通过临时输入/输出文件获得数据，并用退出码决定结果。
- 编辑器切换时自动加载已有用例；`Ctrl+Alt+B` 可创建本地题目或运行当前源码。
- 所有编译器、解释器、额外参数、时限、模板、导入目录和 `ONLINE_JUDGE` 均可配置。

## 快速开始

1. 用 `./gradlew buildPlugin` 构建插件，在 JetBrains IDE 的 **Settings | Plugins | Install Plugin from Disk** 中选择生成的 ZIP。
2. 安装浏览器扩展 [Competitive Companion](https://github.com/jmerle/competitive-companion)。
3. 打开一个本地项目，在题目页面点击 Companion 的绿色加号。
4. 插件会创建默认的 Kotlin 文件并加载样例。点击 **Run Testcases**，或按 `Ctrl+Alt+B`。

Competitive Companion 通过 `localhost:27121` 发送题目。同一时间只保留一个 CPH 监听器；如果 VS Code/Cursor 中的 CPH 也在运行，请先停用其中一个，避免请求被错误的编辑器接收。

不使用浏览器扩展时，也可以打开任一受支持的源码，点击 **Run Testcases** 或按 `Ctrl+Alt+B`。与 VS Code 原版 CPH 一样，第一次操作只会创建一个空白本地题目并打开用例界面；填写用例后再次操作才会编译并运行。

## 工具链

与原版 CPH 一样，插件不内置第三方编译器。只需安装实际使用的工具链；命令可在 **Settings | Tools | CPH** 中修改。

| 语言 | 默认编译命令 | 默认运行命令 |
| --- | --- | --- |
| Kotlin | `kotlinc` | `java` |
| C++ | `g++` | 生成的可执行文件 |
| C | `gcc` | 生成的可执行文件 |
| C# | `dotnet` | `dotnet` |
| Rust | `rustc` | 生成的可执行文件 |
| Go | `go` | 生成的可执行文件 |
| Haskell | `ghc` | 生成的可执行文件 |
| Python | — | `python3` |
| Ruby | — | `ruby` |
| Java | `javac` | `java` |
| JavaScript | — | `node` |
| Cangjie | `cjc` | 生成的可执行文件 |

命令字段只填写可执行文件；额外参数填写在对应参数列。参数支持单引号、双引号和反斜杠转义，但不会交给 shell 解释。

## 自定义 checker

启用 **Custom checker** 并选择 Python 脚本后，每个成功运行的用例会执行：

```text
python3 checker.py <input-file> <actual-output-file>
```

- 退出码 `0`：Accepted。
- 非零退出码：Wrong Answer。
- checker 无法启动、超时或输出过大：Checker Error。
- checker 的 stdout/stderr 会显示在用例结果中。
- 临时文件会在 checker 完成后删除；启用 checker 时预期输出不参与判定。

## 对拍

点击 **Stress Test**，选择生成器和暴力解（任意受支持语言）并设置迭代次数。每轮：

1. 以迭代编号（从 1 开始）作为第一个命令行参数运行生成器，其 stdout 作为输入。
2. 用该输入分别运行暴力解和当前源码，按 CPH 规则逐行比较输出。
3. 首次出现不一致（WA、RE、TLE、输出超限）时停止，并把该输入与暴力解输出作为新用例加入列表。

生成器或暴力解自身失败时报告为错误，而不是当前源码的失败；对拍不使用自定义 checker。

## 用例 JSON

**Import JSON** 支持替换或追加，**Export JSON** 使用相同的可移植格式。单个文件最多 1000 个用例、2 MiB：

```json
[
  {
    "input": "1 2\n",
    "output": "3\n"
  }
]
```

## 数据与安全边界

- HTTP 服务只绑定 IPv4 loopback，不向局域网开放。
- 请求体最大 2 MiB，单题最多 1000 个用例，并校验题目和字段边界。
- 进程参数始终以数组传递，不拼接 shell 命令。
- 单次进程最多捕获 4 MiB 输出，编译产物位于临时目录并在运行后删除。
- `.cph` 元数据使用 UTF-8 和原子替换写入；损坏的元数据不会被静默覆盖。
- **Reset Problem** 只删除 `.cph` 元数据，不删除源码，并在执行前确认。

## 开发与验证

需要 JDK 21 或更新版本运行 Gradle；插件输出 JVM 21 字节码并以 IntelliJ Platform 2025.2 为最低版本。

```shell
./gradlew test
./gradlew buildPlugin
./gradlew runIde --args=/absolute/path/to/a/test-project
./gradlew verifyPlugin
```

逐功能的自动化边界、真实进程测试和手工验收步骤见 [docs/VERIFICATION.md](docs/VERIFICATION.md)。
