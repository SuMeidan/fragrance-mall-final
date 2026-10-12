# 实习生 Git 练习与提交规则

这里用于练习修改文件、提交、推送和提 Pull Request（PR，合并申请）。负责人先审阅，再决定是否把练习内容合并进 `main`。练习完成只说明掌握了协作流程，不代表完成商城业务开发。

## 允许提交什么

当前练习者只允许修改下表中的文件。规则由项目负责人维护；扩大范围前，需要负责人明确分配任务。

| 路径 | 可以写的内容 |
| --- | --- |
| [practice.md](practice.md) | 练习日期、GitHub 用户名、操作步骤、实际结果、遇到的问题与理解 |
| `git-practice/introduction.md`（需要时自行新建） | GitHub 用户名、想学习的技能、可承担的协作事项；不写手机号、学号等个人信息 |
| `git-practice/learning-notes.md`（需要时自行新建） | 用自己的话解释 Git 命令、记录已经核实的学习笔记、整理脱敏后的问题与处理方法 |

规则如下：

1. 只提交上表中的 `.md` 文本文件；本目录的 `README.md` 和仓库根目录的所有文件由负责人维护，练习者不能修改。
2. 正式源码、数据库、依赖、配置、脚本、课程验收、方案、工程日志和 AI 记录均不在练习范围内；以后出现 `backend/`、`storefront/` 等目录，也不能自行修改。
3. 不上传密码、Token、私钥、`.env`、真实个人数据、数据库、日志、安装包、视频或压缩包。报错先检查并脱敏，保留必要文字即可。
4. 内容必须是自己实际做过或理解后的记录。可以用 AI 辅助解释，但要注明用了什么、自己核对了什么；不要复制整段 AI 输出充当完成记录。
5. 使用自己的 GitHub 账号和 Git 提交身份；不借用负责人的账号或凭据。
6. 每次只做一个练习目标，每个新增或修改文件独立提交。提交说明如 `docs: 记录首次 Git 提交练习`，不使用 `update`、`修改一下` 等含糊描述。
7. 只推送 `practice/<自己的GitHub用户名>` 分支；禁止直接推送 `main`、自行合并 PR、强推、改写已推送历史或删除远程分支。
8. 禁止使用 `git add .`、`git add -A`、`git reset --hard`、`git clean -fd`、`git push --force`。发现范围外改动或冲突时，停止操作并把脱敏后的状态交给负责人确认。

以上是协作约定。Git 本身不提供按文件夹分配写权限的功能；本次没有配置 GitHub 分支保护或自动检查，因此这些规则不会在平台上自动阻止违规提交。是否具备推送权限取决于当前仓库授权；只读访问者需要负责人授予写权限才能完成推送练习。

## 先理解五个位置

文件编辑发生在本机的**工作区**；`git add` 把指定改动选入**暂存区**；`git commit` 生成本地版本；`git push` 把版本送到 GitHub 的个人练习分支；PR 请负责人审阅并决定是否合并进主分支 `main`。

所以，提交成功不等于推送成功，推送成功也不等于已经合并。

## 第一次操作

以下命令在 PowerShell 中运行，每个代码块按顺序执行。`intern01` 是示例：先把它替换成自己的 GitHub 用户名；邮箱从 GitHub 的邮箱设置中复制自己的 noreply 地址。不要照填负责人的身份。

### 1. 获取仓库，配置自己的身份

在自己选择的本地父目录运行；如果已经克隆，直接进入现有仓库，跳过克隆命令，不要再建一份。

```powershell
git clone https://github.com/SuMeidan/fragrance-mall-final.git
cd fragrance-mall-final
```

私有仓库需要用自己的账号完成 GitHub 登录。登录失败时不要把密码或 Token 写进命令、文件或截图。

进入仓库后，替换示例身份并执行：

```powershell
git config user.name "intern01"
git config user.email "从GitHub设置复制自己的noreply邮箱"
git config --get user.name
git config --get user.email
git status --short --branch
```

这里没有 `--global`，只设置这个仓库的提交身份。`git config` 不会替你完成 GitHub 登录或授予访问权限。如果状态显示已有修改，先停止，不覆盖已有工作。

### 2. 从最新主分支创建个人练习分支

下面两条创建分支的命令只在首次执行；如果分支已经存在，按后面的“下一次练习”操作。

```powershell
$practiceBranch = "practice/intern01"
git fetch origin
git switch -c $practiceBranch origin/main
git branch --show-current
```

最后应显示自己的 `practice/用户名`。`fetch` 更新远程版本信息；`switch -c` 从 `origin/main` 创建并切换到练习分支，不会修改远程 `main`。每条命令成功后再执行下一条。

### 3. 编辑一个文件，检查后提交

打开 [practice.md](practice.md)，按提示填写学习记录。第一次只填写日期、GitHub 用户名，以及对 `add` / `commit` / `push` 的初步理解；实际推送和 PR 结果留到操作成功后填写。

```powershell
git status --short --branch
git diff -- git-practice/practice.md
git add -- git-practice/practice.md
git diff --cached --name-only
git diff --cached --check
git diff --cached -- git-practice/practice.md
```

检查门槛：当前分支为自己的练习分支；状态没有范围外改动；暂存文件列表只有 `git-practice/practice.md`；差异内容正确；`--check` 没有报错。任何一项不满足都先停下。以后新增另外两个允许文件，也要一次只暂存一个明确路径。

通过后运行：

```powershell
git commit -m "docs: 记录首次 Git 提交练习"
git show --stat --oneline HEAD
git status --short --branch
```

最后应没有未提交的文件变化。`commit` 只创建本地版本，此时 GitHub 页面还没有这次改动。

### 4. 推送个人分支，发起 PR

确认 `git branch --show-current` 仍是自己的 `practice/用户名`，再执行：

```powershell
git branch --show-current
git push -u origin HEAD
git status --short --branch
```

这里 `HEAD` 表示当前分支，`-u` 记住其远程跟踪关系。下一次仍须先核对当前分支，再用 `git push`。

打开[仓库页面](https://github.com/SuMeidan/fragrance-mall-final)，选择自己的练习分支，确认文件内容已经出现；再发起 PR，目标分支（base）为 `main`，来源分支（compare）为自己的 `practice/用户名`。在 “Files changed” 中检查只有允许的练习文件，然后填写：

```text
标题：docs: 完成首次 Git 提交与推送练习

修改文件：git-practice/practice.md
完成内容：说明自己填写了哪些真实学习记录
验证结果：当前分支正确；暂存与 PR 差异只含允许文件；差异检查通过；推送后的页面内容已核对
遇到的问题：没有则写“无”；有则写脱敏后的报错与实际处理
需要负责人审阅：提交范围、记录是否清楚、Git 操作是否理解正确
```

PR 发起后把链接交给负责人，不自行点击合并。推送和 PR 创建成功后，可再更新 `practice.md` 的实际结果，用同样流程形成第二次提交，PR 会随该分支的新推送更新。

## 下一次练习

在工作区干净时进入已有分支并更新；同样先替换示例用户名：

```powershell
$practiceBranch = "practice/intern01"
git fetch origin
git switch $practiceBranch
git pull --ff-only origin $practiceBranch
git status --short --branch
```

`--ff-only` 只接受可以直接前移到的更新，不会替你生成合并提交；出现分叉或冲突就停止并找负责人。如果上次 PR 已合并，先和负责人确认新练习分支名称及主分支同步方式，不继续复用旧 PR。

建议分三次完成：先修改 `practice.md` 并推送、提 PR；再新增 `introduction.md` 并验证 PR 差异；最后新增 `learning-notes.md`，用自己的话解释完整流程。每次练习对应一个清楚的目标与一个文件提交。

## 遇到问题时如何停下来

| 现象 | 该怎么做 |
| --- | --- |
| 暂存了不该提交的文件 | 用 `git restore --staged -- 具体文件路径` 取消暂存，保留本地修改；重新检查状态，范围外改动交给负责人处理 |
| `nothing to commit` | 检查编辑器是否已保存、`git diff` 是否存在改动、是否暂存了指定文件；不要反复提交空版本 |
| 推送提示 `403`、认证失败或仓库不存在 | 核对仓库地址、当前登录账号与写权限；请负责人确认授权，不分享凭据 |
| 推送提示 `non-fast-forward` / `rejected` | 保留改动与报错，请负责人检查分支差异；不使用强推 |
| 文件出现冲突标记或分支名不符合预期 | 不继续提交或推送；提供 `git status --short --branch` 和脱敏后的报错 |

练习验收：GitHub 上自己的分支有真实提交；PR 只包含允许文件且由负责人审阅；能解释本地提交、远程推送、PR 合并的区别。负责人合并后才算进入主分支。
