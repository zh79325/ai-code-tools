# 开发计划：微信公众号文章草稿发布功能

## 1. 需求理解

**需求**：开发一个功能，能够读取本地Markdown文件，将其中的图片上传到微信公众号素材库，转换为微信公众号支持的格式，然后调用微信公众号API创建草稿保存到公众号草稿箱。

**具体要点**：
- 输入：本地Markdown文件路径
- 处理：
  1. 解析Markdown，找出所有本地图片
  2. 将每张本地图片上传到微信公众号素材库
  3. 替换图片链接为公众号素材URL
  4. 将Markdown转换为微信公众号兼容的HTML格式
- 输出：调用微信公众平台API，在公众号草稿箱创建一篇草稿
- 开发位置：`ai-code-core`模块
- 不包含：直接发布、草稿更新等功能，仅创建草稿

## 2. 架构设计

### 2.1 整体方案

基于微信公众平台官方API实现，主要流程：

```
本地Markdown文件
    ↓
读取文件内容
    ↓
解析Markdown，提取所有图片引用
    ↓
对每个本地图片：
    ↓
    调用 API 上传到素材库
    ↓
    获取media_id，替换图片URL
    ↓
Markdown → HTML转换
    ↓
HTML处理（适配公众号格式）
    ↓
获取 access_token
    ↓
调用 add_draft API 创建草稿
    ↓
返回草稿信息
```

### 2.2 需要新增的文件

按用户要求简化，**全部代码写到一个工具类文件**：

在 `ai-code-core/src/main/java/com/ai/tools/core/wechat/` 下创建：

```
wechat/
└── WechatMarkdownPublisher.java  # 微信公众号Markdown发布工具类（全部功能在此）
```

**所有功能都在这个单一工具类中**：
- 配置存储（appid, secret）
- 模型数据（用内部类记录响应）
- access_token 获取和缓存
- Markdown图片提取
- 图片上传素材库
- 链接替换
- Markdown转HTML
- 创建草稿API调用

### 2.3 依赖修改

- 需要在 `ai-code-core/pom.xml` 添加依赖：
  - CommonMark（Markdown转HTML）：`org.commonmark:commonmark`
  - OkHttp（已在父pom有依赖，可直接使用）

### 2.4 微信公众号API说明

使用微信公众平台官方API：
1. 获取access_token：`GET https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=APPID&secret=APPSECRET`
2. 上传图片素材：`POST https://api.weixin.qq.com/cgi-bin/material/add_material?access_token=ACCESS_TOKEN&type=image`
3. 创建草稿：`POST https://api.weixin.qq.com/cgi-bin/draft/add?access_token=ACCESS_TOKEN`

API文档：
- 获取access_token：https://developers.weixin.qq.com/doc/offiaccount/Basic_Information/Get_access_token.html
- 上传素材：https://developers.weixin.qq.com/doc/offiaccount/Asset_Management/New_temporary_materials.html
- 创建草稿：https://developers.weixin.qq.com/doc/offiaccount/Draft_Management/Add_draft.html

## 3. 实现步骤

### 步骤1：配置依赖
- 在 `ai-code-core/pom.xml` 添加CommonMark依赖

### 步骤2：创建单一工具类
- 创建 `WechatMarkdownPublisher.java`
- 定义内部记录类用于API响应
- 实现配置构造方法（传入appid, secret）

### 步骤3：实现基础API方法
- 实现获取access_token（支持简单内存缓存）
- 实现上传图片素材API
- 实现创建草稿API

### 步骤4：实现Markdown处理
- 解析Markdown提取所有本地图片路径
- 批量上传图片到素材库
- 替换本地路径为微信URL

### 步骤5：实现Markdown转HTML
- 使用CommonMark转换为HTML
- 适配公众号样式

### 步骤6：实现主入口方法
- `publishDraft(String markdownFilePath, String title)` 完整流程
- 返回创建结果（mediaId和url）

### 步骤7：验证
- 编译验证代码正确性

## 4. 注意事项

### 4.1 依赖兼容性
- Java 21 环境，CommonMark 0.21.0 以上版本兼容
- OkHttp 已在父pom中定义版本，直接使用即可

### 4.2 配置管理
- appid 和 secret 通过配置传入，不硬编码
- access_token 需要简单缓存，避免频繁调用

### 4.3 Markdown转换适配
- 微信公众号对HTML标签支持有限，只转换常用标签：
  - 标题 `#` → `<h1>`/`<h2>`/`<h3>`
  - 粗体 `**` → `<strong>`
  - 斜体 `*` → `<em>`
  - 链接 `[text](url)` → `<a>`
  - 列表 `-`/`*` → `<ul>/<li>`
  - 代码块 ``` → `<pre><code>`
  - 换行 → `<br>`

### 4.4 异常处理
- 使用项目已有的 `BizException` 抛出业务异常
- 统一错误处理，返回清晰的错误信息

### 4.5 图片处理
- 支持相对路径和绝对路径的本地图片
- 网络图片保持原链接不变，不上传
- 图片格式支持：JPG、PNG、GIF（微信公众号支持的格式）
- 上传成功后获取图片URL替换原链接

### 4.6 测试
- 需要用户提供有效的公众号appid和secret进行测试
- 公众号需要已认证才能使用草稿管理API
