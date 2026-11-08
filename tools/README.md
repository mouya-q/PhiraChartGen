# 工具

## validate_phira_pack.py

谱面包校验器, 只依赖 Python 3 标准库

```
python3 tools/validate_phira_pack.py 谱面包.zip
```

## prototype/

算法原型 (Python + numpy). Kotlin 实现 (`core/`) 按其逻辑移植, 用于在没有 Android 工具链时验证算法

| 文件 | 内容 |
| --- | --- |
| synth.py | 合成测试音频: 底鼓, 军鼓, 踩镲, 持续音, 琶音 |
| analysis.py | 起音检测与 BPM 估计 |
| gen.py | 谱面生成 |
| export.py | 三种格式导出 |
| build_samples.py | 生成 samples/ 下的 12 个演示谱面包 |

重新生成样例:

```
pip install numpy
python3 tools/prototype/build_samples.py
python3 tools/validate_phira_pack.py samples/*.zip
```

原型与 Kotlin 实现不是逐位一致 (例如窗函数与随机数不同), 以 Kotlin 实现为准
