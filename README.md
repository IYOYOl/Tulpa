# Tulpa

**把你想见到的人，自然地放进你刚拍的照片里。**

Tulpa 是一款极简 Android 相机：按下快门之后，AI 会先"看懂"画面，再决定人物该站在哪、在做什么、光影怎么接，最后把参考人物合成进照片——像一张现场拍下来的照片，而不是贴图。

- Kotlin + Jetpack Compose + CameraX
- 双密钥：**DeepSeek** 负责看图与写提示词，**火山方舟 Seedream** 只负责生图
- 开源协议：[MIT License](LICENSE)

---

## 功能

### 相机
- 锁竖屏、全屏沉浸，取景框上方只有：闪光灯（关/自动/开）、比例（3:4 / 16:9）、镜头感、二次元/三次元、设置
- 照片方向跟随手机实际朝向：竖拍出竖图，横拍出横图
- 变焦胶囊滑动条：按设备实际范围显示刻度，点刻度吸附、拖动连续变焦
- 快门即入队，可以连续拍，不用等上一张生成完

### 生成队列
- 串行处理，顶部显示「正在生成 N 张」，点开可查看队列
- 状态：排队中 / 正在分析 / 正在生成 / 已完成 / 失败（附原因）
- 失败可单张重试；队列 worker 异常会自愈，不会卡死在"排队中"

### 智能体（3 个角色 + 导演，共 3 轮大模型调用）
1. **视觉分析员**：拆解空间结构、镜头、光线、物体朝向与排列、可交互点、穿帮风险
2. **摄影师 / 灯光师 / 合成师**：分别从构图、光影、违和风险给出意见
3. **导演**：汇总意见，产出最终生图提示词

分析时会同时看到**场景照片**与**参考人物照片**，所以能判断人物风格（真人 / 二次元）并做相应处理。

### 风格开关
- **二次元**：保留动漫/插画渲染
- **三次元**：把参考人物渲染成真人照片质感
- **镜头感**：开 = 人物知道在被拍（会看镜头）；关 = 自然抓拍

### 图库
- 只显示本 App 生成的照片，可看合成图 / 原图、完整提示词、AI 思考过程
- 支持**重新合成**：输入你的想法（如"让她坐在靠窗位置"），重新跑一遍智能体
- 成片自动存入系统相册 `Pictures/Tulpa`；App 内删除会同步删除相册里那张（含二次确认）

---

## 构建

### 环境
- Android Studio / Android SDK 36（compileSdk 36，minSdk 26）
- JDK 17
- Gradle 8.13（仓库自带 wrapper）

### 步骤
```bash
git clone https://github.com/IYOYOl/Tulpa.git
cd Tulpa
```

在根目录新建 `local.properties`（不要提交）：
```properties
sdk.dir=/path/to/Android/Sdk
```

构建：
```bash
./gradlew assembleDebug      # 调试包
./gradlew assembleRelease    # 发布包（默认用 debug 签名，可直接安装）
```

产物：`app/build/outputs/apk/<variant>/`

---

## 使用

1. 打开 App → 右上角设置
2. **① DeepSeek**：填入 API Key（看图 + 写提示词，模型需支持图片输入，例如 `deepseek-flash`）
3. **② 火山方舟**：填入 API Key 与生图模型（需支持参考图，例如 `doubao-seedream-5-0-flash`）
4. **③ 参考人物照片**：上传一次，之后每次拍照自动使用
5. 回到相机 → 拍照 → 等队列生成 → 图库里查看

> 两个 Key 只保存在本机 `SharedPreferences`，不会上传到任何第三方服务器（除了你配置的官方 API）。

---

## 目录结构

```
app/src/main/java/com/lxylx/aicamera/
├── MainActivity.kt          # 入口、页面切换、权限
├── AppViewModel.kt          # 全部状态 + 生成队列 worker
├── data/                    # Prefs、PhotoItem、PhotoStore（本地图库）
├── net/ApiClient.kt         # DeepSeek 对话、火山方舟生图、多角色智能体
├── ui/                      # 相机 / 设置 / 图库 / 队列 / 变焦条 / 主题
└── util/                    # Aspect 比例、ImageUtils 图片处理与相册读写
assets/icon_1024.png         # 图标母版
docs/
├── prompt-pipeline.md       # 提示词流水线设计说明
└── make_icon.py             # 从母版生成各密度自适应图标
```

---

## 技术栈

| 组件 | 版本 |
|---|---|
| Kotlin | 2.2.0 |
| AGP | 8.13.0 |
| Gradle | 8.13 |
| Jetpack Compose | BOM 2025.06.01 |
| CameraX | 1.4.2 |
| OkHttp | 4.12.0 |
| Coil | 2.7.0 |

### 外部接口
- **DeepSeek**：`POST https://api.deepseek.com/chat/completions`（多图输入，`thinking` + `reasoning_effort: high`）
- **火山方舟**：`POST https://ark.cn-beijing.volces.com/api/v3/images/generations`（`image` 传场景图 + 参考图，`size` 控制比例）

> 注意：Seedream 系列接口**没有** `negative_prompt` 字段，负向约束需写进正向提示词。

---

## 已知限制

- 合成是"生成"而不是像素级抠图，背景会保持但无法做到逐像素不变
- 参考人物是二次元时，转真人效果取决于生图模型能力
- 提示词长度上限 300 字；比例由 `size` 参数 + 本地裁切保证
- 队列串行，单张生成耗时取决于模型（5.0 pro 较慢，客户端超时 10 分钟）

---

## 免责声明

- 请只使用**你本人**或**已获得明确授权**的人物照片作为参考
- 禁止用于伪造他人形象、冒充身份、诈骗或其他违法用途
- 使用者需自行承担因使用本软件产生的一切法律责任

---

## License

[MIT License](LICENSE) © 2026 IYOYOl

欢迎 Issue 与 PR。

---

## English

**Tulpa** is a minimal Android camera that inserts a reference person into the photo you just took — naturally lit, correctly placed, no prompt writing needed. Built with Kotlin, Jetpack Compose and CameraX. A multi-role LLM agent (analyst → photographer/lighting/compositor → director) writes the generation prompt, then Volcengine Ark Seedream renders the result.

Setup: provide a DeepSeek API key (vision + prompt) and a Volcengine Ark key (image generation), upload one reference photo, and shoot.

MIT licensed.
