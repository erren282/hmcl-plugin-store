# 插件商店

[![HMCL Plugin](https://img.shields.io/badge/HMCL-plugin-blue)](https://github.com/topics/erren282s-launcher-plugins)
[![Version](https://img.shields.io/badge/version-1.8.2-green)]()
[![License](https://img.shields.io/badge/license-GPL--3.0-lightgrey)](LICENSE)

在 HMCL 里浏览并一键安装插件：自动从 GitHub topic 抓取插件列表，显示 star 数与简介，点一下就能装。

- **插件 ID**：`org.dsh.plugin-store`
- **适用启动器**：[erren282-launcher](https://github.com/erren282/erren282-launcher)（HMCL 3.16.4 定制版，需内建插件系统）
- **数据源**：GitHub topic [`erren282s-launcher-plugins`](https://github.com/topics/erren282s-launcher-plugins)

## 功能

### 浏览

- **自动发现**：通过 GitHub 搜索 API 拉取所有打了该 topic 的仓库，无需手工维护索引文件
- **插件列表**：每个插件显示名称、★ star 数量与仓库简介（两行截断，收在小方框内）
- **Markdown 正文**：点「显示全部」用启动器自带的 `MarkdownRenderer` 渲染该仓库 README，
  固定高度视口内滚动，不会把卡片撑高
- **本地搜索**：按名称、简介或仓库名过滤，输入即时生效，不重复请求网络
- **暗色模式**：颜色全部取自启动器 `-monet-*` 主题变量，跟随明暗主题自动切换
- **磨砂背景**：页面半透明并叠加 `BoxBlur`，与启动器其它页面的壁纸观感一致

### 安装与更新

- **一键安装**：自动找到该仓库最新 Release 里的插件 jar，下载并交给启动器安装
- **下载进度**：进度条 + 百分比 + 已下载/总大小
- **更新检测**：已装插件与最新 Release 不同时显示「可更新到 vX」，按钮变为「更新」
- **已装识别**：已是最新版时按钮置灰为「已安装」
- **镜像加速**：下载按 `GitHub 原始地址 → ghfast.top → gh-proxy.com` 顺序尝试，失败自动切换
- **安装后提示重启**：启动器只在启动时加载插件，因此每次启动后的**第一次**安装完成后会弹窗提示，
  点「立即重启」即重启启动器让新插件生效；同一次会话里后续的安装不再重复弹窗
- **自动重启更新**：无法原地替换运行中的 jar，因此下载完成后暂存，点标题栏的重启按钮即自动完成替换并重启

## 在哪里找到它

商店页注册在**插件模块**里，而不是主窗口侧边栏：

**设置 → 插件 → 「插件商店」**

## 安装本插件

把 `org.dsh.plugin-store_1.8.2.jar` 放进启动器目录的 `.hmcl/plugins/`，
重启启动器或在「设置 → 插件」里点击「重新加载插件」。

## 更新是如何完成的

运行中的插件 jar 被类加载器映射，Windows 拒绝删除或重命名这样的文件。
因此更新分两步：

1. **下载并暂存**：新 jar 存到插件数据目录的 `pending/` 下，并写入 `pending-updates.json`
2. **重启时替换**：点标题栏的重启按钮会生成一个批处理脚本，它等待启动器进程退出，
   删除同 ID 的旧 jar、复制新 jar，然后重新启动启动器

这样不需要手动干预，也不会残留两个版本。

## GitHub 接口限流

未认证的 GitHub API 每小时只有 60 次额度，而商店要为每个仓库拉取 Release 信息。
额度耗尽时版本列表会返回空，更新检测随之失效。

两种缓解措施：

- **结果缓存**：同一仓库在一个会话内只请求一次
- **可选 token**：在插件数据目录写入 `github-token.txt`（内容为 GitHub
  personal access token，只需 public repo 读权限），额度提升到 5000 次/小时

```
.hmcl/plugins/data/org.dsh.plugin-store/github-token.txt
```

## 工作机制

| 步骤 | 实现 |
| --- | --- |
| 列出插件 | `GET /search/repositories?q=topic:erren282s-launcher-plugins` |
| 展开简介 | `GET /repos/{owner}/{repo}/readme`（`Accept: application/vnd.github.raw`） |
| 版本列表 | `GET /repos/{owner}/{repo}/releases`，只保留带 jar 的 Release |
| 找安装包 | `GET /repos/{owner}/{repo}/releases/latest`，优先取 `org.dsh.*.jar` |
| 下载安装 | 下载到插件数据目录，再调用 `PluginManager.install(Path)` |

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
