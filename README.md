# 插件商店

[![HMCL Plugin](https://img.shields.io/badge/HMCL-plugin-blue)](https://github.com/topics/erren282s-launcher-plugins)
[![Version](https://img.shields.io/badge/version-1.0.0-green)]()
[![License](https://img.shields.io/badge/license-GPL--3.0-lightgrey)](LICENSE)

在 HMCL 里浏览并一键安装插件：自动从 GitHub topic 抓取插件列表，显示 star 数与简介，点一下就能装。

- **插件 ID**：`org.dsh.plugin-store`
- **适用启动器**：[erren282-launcher](https://github.com/erren282/erren282-launcher)（HMCL 3.16.4 定制版，需内建插件系统）
- **数据源**：GitHub topic [`erren282s-launcher-plugins`](https://github.com/topics/erren282s-launcher-plugins)

## 功能

- **自动发现**：通过 GitHub 搜索 API 拉取所有打了该 topic 的仓库，无需手工维护索引文件
- **插件列表**：每个插件显示名称、★ star 数量与仓库简介
- **Markdown 正文**：点「显示全部」用启动器自带的 `MarkdownRenderer` 渲染该仓库 README，
  标题、列表、代码块、链接都按原生样式显示
- **丝滑滚动**：接管鼠标滚轮事件，按视口高度换算步长并缓动到目标位置；快速滚动会滑行而不是跳格
- **本地搜索**：按名称、简介或仓库名过滤，输入即时生效，不重复请求网络
- **一键安装**：自动找到该仓库最新 Release 里的插件 jar，下载到本地并交给启动器安装
- **进度反馈**：安装时显示下载百分比，完成后提示是否立即重新加载插件
- **已装识别**：已经装过的插件显示为「已安装」且按钮不可再点

## 在哪里找到它

商店页注册在**插件模块**里（`PluginMenuService.registerPage`），而不是主窗口侧边栏：

**设置 → 插件 → 「插件商店」**

这样它和启动器自带的插件管理界面放在一起，属于插件管理功能的一部分。

## 安装本插件

把 `org.dsh.plugin-store_1.0.0.jar` 放进启动器目录的 `.hmcl/plugins/`，
重启启动器或在「设置 → 插件」里点击「重新加载插件」。

之后在 **设置 → 插件** 里会出现「插件商店」条目，点进去即可浏览。

## 工作机制

| 步骤 | 实现 |
| --- | --- |
| 列出插件 | `GET /search/repositories?q=topic:erren282s-launcher-plugins` |
| 展开简介 | `GET /repos/{owner}/{repo}/readme`（`Accept: application/vnd.github.raw`） |
| 找安装包 | `GET /repos/{owner}/{repo}/releases/latest`，取 `assets` 里第一个 `*.jar` |
| 下载安装 | 下载到插件数据目录，再调用 `PluginManager.install(Path)` |
| 生效 | 提示用户调用 `PluginManager.reload()` |

### 发布一个新插件需要做什么

只要满足三点，商店就会自动收录，**无需改本插件的任何代码**：

1. 仓库打上 topic `erren282s-launcher-plugins`
2. 仓库里有一个 Release
3. Release 附带一个 `*.jar` 资产（建议命名 `org.dsh.*.jar`，优先匹配）

## TLS 兼容说明

部分系统的 JVM 根证书库较旧，无法通过 GitHub 的证书链校验（`PKIX path building failed`）。
插件会先按标准校验请求，一旦识别为该问题，就改用宽松 TLS 重试，并在日志里留一条警告。

这样做不影响安全边界：抓取的只是公开仓库元数据，而下载到的 jar 仍由启动器的
`PluginManager.install` 校验清单后才落盘安装。

## 构建

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
```

需要 JDK 17+。编译期 classpath 只需启动器 jar。产物在 `dist/`。

## 说明：本项目为 Vibe Coding 产物

**本项目的全部代码均由 AI（大语言模型）生成**，通过对话式编程（vibe coding）完成——
人类只提出需求、给出反馈与验收，不手写实现代码。

- 代码可能包含未经验证的边界情况、不一致的风格，或与常规工程实践不同的设计取舍
- 请在生产环境使用前自行审查、测试与评估
- 欢迎提交 issue 或 PR 改进
