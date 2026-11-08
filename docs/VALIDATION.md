# Phira 兼容性验证

## 谱面包结构

导出的 zip 在根目录直接放置 4 个文件, 不含文件夹:

| 文件 | 说明 |
| --- | --- |
| `info.yml` | 元数据: name, difficulty, level, charter, composer, illustrator, chart, music, illustration, previewStart, aspectRatio, backgroundDim, lineLength, offset, tags, intro, holdPartialCover |
| `chart.json` 或 `chart.pec` | 谱面 |
| `song.mp3` / `song.ogg` / `song.wav` | 音频 |
| `background.png` | 插图, 优先使用音频内嵌封面 |

结构依据 Phira 社区文档 (phira-docs) 中的谱面信息格式

## 三种格式的状态

| 格式 | 状态 |
| --- | --- |
| RPE JSON | 推荐. 结构通过校验器, 待实机确认 |
| Phigros 官方 JSON | 结构通过校验器. 速度与坐标换算依据公开资料推算, **待实机确认** |
| PEC | 结构通过校验器. 坐标与速度换算依据公开资料推算, **待实机确认** |

"待实机确认"表示尚未在 Phira 中导入验证. 完成验证后请在下面的表格记录结果

## 验证方法

### 1. 自动校验

应用导出后会自动校验. 也可以在电脑上运行:

```
python3 tools/validate_phira_pack.py 谱面包.zip
python3 tools/validate_phira_pack.py samples/*.zip
```

检查内容: 包结构, info.yml 必填项, 文件存在, 谱面格式的必要字段, 拍数合法, 事件连续, 音符类型, Hold 时长, 音符数量

通过只代表结构合规, 不代表 Phira 一定能正确加载

### 2. 在 Phira 中导入

1. 把导出的 zip 传到手机
2. 在 Phira 中选择导入谱面, 或用 Phira 打开该文件
3. 进入谱面, 检查:
   - 能正常加载, 没有报错
   - 音乐与第一个音符的时间对得上, 整体偏早或偏晚时用设置中的谱面延迟校准后重新导出
   - 音符速度合适, 判定线位置与摆动正常
   - 长按, 滑动, 拖动的判定正常

### 3. 样例

`samples/` 目录有由程序合成的 40 秒演示曲生成的 12 个谱面包 (4 个难度 x 3 种格式). 其中插图是占位的 1x1 图片, 仅用于结构验证

## 实机验证记录

| 日期 | Phira 版本 | 设备 | 格式 | 结果 | 备注 |
| --- | --- | --- | --- | --- | --- |
| | | | | | |
