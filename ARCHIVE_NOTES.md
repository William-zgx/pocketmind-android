# 归档 Tag 说明

本仓库的 `archive/*` 与 `backup/*` tag 保存了历史分支与在制代码的快照。
2026-09-01 的分支清理中,本地 61 个分支归约为 `main` 一条线;所有被删除的内容
都固定在下列 tag 中,可随时取回。

## 结论摘要

清理前对全部分支做过能力审计。判定依据是确定性证据(树哈希相同、patch-id 逐一
匹配、符号级比对),而非 diff 大小。**除下述一项外,没有任何分支持有 `main`
缺失的能力**——多数分支是同一条线性链的历史快照、squash 合并前的旧副本,或
`gemmalocalqa` → `pocketmind` → `solin` 两轮包重命名造成的表面差异。

## archive/* — 能力簇快照

| Tag | 说明 |
|---|---|
| `archive/zvec-selfcontained-native` | **唯一真正独占的资产。** 728 行纯 STL 的自包含 C++ 平坦向量索引(`app/src/main/cpp/zvec_bridge.cpp`),含余弦相似度与 `partial_sort` top-k,自带 `ZVECJNI1` 二进制快照格式。`main` 的 `zvec_native_store.cpp` 改为链接预编译 `libzvec_c_api.so`,缺库时 CMake 会 `FATAL_ERROR` 硬失败;此实现不依赖任何预编译产物。局限:O(n) 全扫描,无 ANN/量化,且位于旧 `pocketmind` 包。 |
| `archive/agent-loop-chain` | 17 个 `agent-loop-*` 分支构成的严格线性链的链尾,包含其余 16 个的全部提交。九项能力在 `main` 中均有对应实现,且八项更完善。 |
| `archive/multi-agent-capability-gates` | 已由 squash 方式并入 `main`。 |
| `archive/screen-ocr-optimization` | 屏幕观测优化;`main` 已具备等价能力。 |
| `archive/zvec-memory-integration` | zvec 记忆集成实验。 |

## archive/wip-* — 抢救的未提交代码

三个 `.trae` worktree 中存在**未提交、且不在任何分支上**的在制代码,清理 worktree
会使其永久消失,故先提交固化再归档。基线为 `4ede5d1f`(2026-07-14),早于
`main`(2026-08-06),因此**不可直接合并**,仅作设计参考。

| Tag | 内容 |
|---|---|
| `archive/wip-implement-tool-authorization-boundary` | `ToolExecutionAuthorizer.kt`(60 行,`main` 版为 29 行)。引入 fail-closed 的 `ToolExecutionAuthorizationContext`:确认状态与能力集为 null 时视为歧义并拒绝,并在 dispatch 前重新校验 registry 与安全策略。这是 `main` 中没有的设计思路。 |
| `archive/wip-implement-skill-package-core` | 外部技能包的 models / validator / 安全解包器,均比 `main` 对应文件更长。 |
| `archive/wip-build-stream-state-reducer` | `GenerationStreamReducer.kt`(120 行)。`main` 已有同名正式实现。 |

## backup/* — 历史安全快照

`pocketmind` 时代(2026-06-07)执行 squash/fixup 前留下的保险快照,内容不在 `main`
的历史中。保留以备追溯,无现役价值。

## 取用方式

```bash
git tag -l 'archive/*'                                   # 列出归档
git show archive/<tag>:<path>                            # 查看单个文件
git checkout -b <new-branch> archive/<tag>               # 拉回成分支
git diff main archive/<tag> -- <path>                    # 与 main 比对
```
