# 知光 Feed 流 · Vibe 知识短视频

当前阶段：A“透明分拣线”75 秒正式成片已完成，并通过全片解码、关键帧、小屏与响度检查。

## 当前工作假设（均可改）

- 独立单片
- 约 75 秒（已按此规格进入制作）
- 1920×1080，30fps
- 无旁白，屏幕短句 + 代码合成配乐
- 不使用外部图片，全部由代码自绘
- 核心结论：知光 Feed 把顺序、内容与用户状态拆开；ZSet 索引决定看什么，分层缓存决定从哪里拿，事件投影决定变化如何到达

## 目录

- `sources.md`：事实与官方来源
- `文章.md`：查证后的知识材料
- `文案.md`：逐幕短句与事实映射
- `找图.md`：素材与许可计划
- `风格方案.md`：A/B/C 三套视觉方向
- `分镜.md`：A 方向的 12 镜头正式分镜
- `brief.md`：最终制作规格与约束
- `风格样图/`：同一开头的三张可见样图
- `scripts/`：可重渲脚本
- `out/zhiguang-feed-flow-75s-final.mp4`：最终交付成片
- `out/first10.mp4`：已确认的 10 秒风格样片
- `qa/review_log.md`：最终质检与迭代记录
- `qa/sheet.png`、`qa/sheet-phone.png`：整片节奏与小屏检查图
- `qa/verify/`：最终文件起始、中间、结尾帧及元数据

## 最终规格

- 时长：75.00 秒
- 画面：1920×1080，30fps，H.264 High，2250 帧
- 声音：AAC LC，48kHz 双声道，无旁白
- 成片响度：-15.3 LUFS；True Peak：-1.5 dBFS
- 文件大小：4,610,410 bytes

## 重渲

在本目录执行：

```powershell
$env:NODE_PATH='C:\Users\Achan\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\node_modules'
$env:PATH="$PWD\scripts\bin;$env:PATH"
node scripts\render.mjs index.html out\final.mp4 --size 1920x1080 --fps 30 --duration 75 --audio audio\mix.wav --workers 4
```

## 已知限制

- 当前机器没有 Maven 命令，尚未完成 Java 编译与测试验证。
- 项目内已准备 FFmpeg 运行包，可直接重渲 MP4；`verify_video.py` 在缺少 ffprobe 时会使用 ffmpeg 回退探测。
