# 拍拍贷DAS简介
拍拍贷DAS是拍拍贷自研的数据库访问框架。包括数据库控制台das console，数据库客户端das client和数据库服务端das server三部分。是基于Java语言开发的，支持数据库管理，ORM，SQL创建，分库分表操作的一体化数据库访问解决方案。

为适应企业规模不断扩大，数据库访问量持续增长的情况，DAS支持两种数据库访问模式，数据库直连和基于das server的代理访问模式。具体场景如下：
* 在数据库可以承受的情况下可以使用直连模式
* 如果访问量持续增长，可以使用分库分表来分摊单个数据库的压力
* 在已经使用了分库分表情况下，如果单库访问量依然达到或超过数据库极限的话，建议使用基于代理的模式。
* 用户的开发环境包括多种语言，建议使用基于代理的模式，配合基于thrift生成的对应客户端

DAS原生提供的客户端das client支持Java，该客户端同时支持直连和代理模式，用户在两种模式之间切换无需修改代码。

DAS支持SqlServer和MySql两种数据库。

目前在拍拍贷超过200个应用接入了DAS，每日支持超过6亿次的数据库访问。

#  DAS功能简介
DAS功能包括：
* 数据库管理。用户可以通过das console来集中的管理物理数据库，配置逻辑数据库，并在不同环境间方便的同步这些配置信息
* 代码生成。用户可以通过das console生成对应于表或者查询结果的entity类
* ORM。用户可以使用das console生成的类通过das clientAPI来获取查询结果和更新数据库记录
* SQL创建。用户可以利用SqlBuilder和das console创建的对应于表的entity类来自由的创建符合语法规范和需要的SQL语句
* 透明的分库分表。通过使用缺省或自定义的策略，用户可以方便的访问多个数据库或表的分片
* 事务。DAS通过非常简单的API支持基于单库的事务，事务的创建，提交或回滚无需用户介入。

## 注意
DAS不支持分布式事务，如果在事务中发现试图访问非当前数据库以外的其他数据库，DAS会报错并回滚当前事务

# DAS使用方式
一般来说，为了使用DAS，建议用户成立一个虚拟的DAS支持团队，由该团队统一负责团队，项目，数据库配置，问题解答等日常维护工作。使用DAS的一般步骤如下
## 配置数据库
应用开发人员向DAS支持团队提交创建DAS团队，DAS项目和逻辑数据库的申请。申请包括团队成员，项目信息，物理数据库配置，逻辑数据库配置和分库分表策略。具体做法参考das console的 使用手册
## 生成代码
DAS支持团队配置好应用的逻辑数据库之后，应用开发人员可以登陆das console，选取自己所在的项目，想生成代码的逻辑数据库，生成对应于表或者查询结果的entity类
## 访问数据库
应用开发人员通过das client的API和生成的entity来对逻辑数据库做CRUD操作。具体请参考das mclient使用手册

# DAS开发团队
## 发起人
DAS最开始是由前拍拍贷基础框架负责人杨波发起，并获得时任CTO司晋琦的大力支持。具体研发分工如下：

## 开发人员
* 产品经理，架构，客户端das client：赫杰辉
* 服务端das server，数据库同步插件，mybatis代码转换插件：卢声远
* 控制台das console：王亮
* 测试：张昱

## 历史贡献者：
* das client与配置中心集成，DasDiagnose实现：黄印煌
* das client早期版本测试：周小亮

# 拍拍贷DAS与携程DAL之间的关系
2018年4月DAS最开始开发的时候是基于携程DAL做的定制化改造，后面在不断到升级中逐渐替换掉携程原有代码，目前除了客户端最底层的部分代码外，基本上已经是一个全新的产品。DAS与DAL的不同体现在以下几个方面：
* DAS重新设计了核心的分库分表策略，支持基于表达式和其组合的分库分表方案
* DAS简化了DAO设计，通过DasClient类支持原来需要DalTableDao，DalQueryDao和自定义DAO才能实现的全部功能
* DAS重新设计的entity的结构。加入了表结构相关的帮助类，让用户可以用SQL自身的语法规则来构建自定义SQL
* DAS重新设计了SqlBuilder。用一个SqlBuilder取代了原来种类繁多的各种builder
* DAS透明的支持本地直连和基于代理的数据库连接模式，允许用户在数据库不断增长的情况下平滑升级整体架构

# 技术支持
TBD
