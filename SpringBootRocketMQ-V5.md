# RocketMQ5.x学习笔记

- 项目环境：SpringBoot版本3.5.7、RocketMQb版本5.2.0、JDK17、Docker版本28.5.1

## RocketMQ5.0版本简介

### 1.RocketMQ的组成

RocketMQ是一个C/S**(Client/Server)**或者B/S**(Browser /Server)**架构,我个人更喜欢称之为C/S架构，即客户端Client与服务端Server，在客户端中分别有Producer和Consumer，而服务端则是由NameServer、Proxy、Broker组成。

#### 客户端

##### Producer（消息生产者

启动时连接NameServer获取Proxy信息，连接Proxy，，使用SDK或THHP/gRPC发送消息，Proxy根据Topic（主题）查找对应的Broker，通过内部协议将消息写入Broker，Proxy把ACK（Acknowledgment）的结果返回给Producer

![image-20251029135716734]([./RocketMQ图床/生产者流程.png](https://github.com/ChenQuan5/spring-boot-rockermq-v5-demo/blob/master/%E6%B6%88%E8%B4%B9%E8%80%85%E6%B5%81%E7%A8%8B.png))

##### Consumer（消息消费者）

启动时连接NameServer获取Proxy信息，连接Proxy订阅Topic（主题），Proxy代注册为ConsumerGroup(消费者分组)并从Broker拉取消息，将消息推送/拉取给Consumer，Consumer确认消费后Proxy再更新Broker的消费进度。

![image-20251029135953286](./RocketMQ图床/消费者流程.png)

#### 服务端

##### **NameServer（注册中心）**

存储所有的Broker和Proxy的元数据，在Producer启动时会先连接NameServer获取Topic-Queue-Broker/Proxy的映射关系

##### Proxy（统一接入层）

接受Producer发来的消息，转发给对应的Broker或者从Broker拉取消息在发给Consumer。

##### Broker（消息中枢

消息核心存储节点，保存所有的消息、消费进度、队列状态，只负责消息数据。向NameServer注册元信息，不会直接面对Producer/Consumer	

----

### 2.应用场

使用消息中间件的最主要目的：

1. **引用解耦**
   - **核心思想**：当系统A需要通知系统B处理某件事，但又不希望因为系统B的故障或者延迟影响自身时，可以通过消息中间件间接通信
     1. **传统紧耦合模式：**
        - 用户下单后，订单系统调用物流系统的接口创建物流订单
        - **问题**：如果物流系统整在发布、宕机或者网络不同导致订单系统调用失败，导致用户无法下单成功，业务中断。
     2. **使用中间件解耦后：**
        - 用户下单后，订单系统将订单数据持久化到数据库，状态为“已支付”
        - 订单系统向RocketMQ发送一条消息，**Topic(主题)**为 `ORDER_CREATED`，消息体包含订单ID，收货地址,用户ID等等信息
        - 发送成功后，订单系统即向用户返回下单成功。
        - **此时订单的业务流程已经结束**
        - 物流系统订阅了`ORDER_CREATED`Topic(主题)收到消息后，开始创建物流订单。即使是物流系统宕机一小时，消息也会持久化保存在RocketMQ中。等物流系统恢复后继续消费这条消息完成物流订单的创建，最终保证数据的一致性。
   - **解耦的价值**：将**直接、同步**的系统调用转变为**简介的、异步的**消息传递，发送者不关心谁来消费，只需确保消息的发出即可。

----

1. **异步处理**
   - **核心思想：**将流程中非核心、耗时的操作从主链路中剥离出来，异步执行加速主流程的响应。
     1. **同步处理模式：**
        - 用户注册 -> 写入数据库 **-> 发送欢迎邮件 -> 初始化用户积分 -> 为用户生产个性推荐** -> 返回结果
        - 问题：后三个步骤（**邮件、积分、推荐**）可能是否耗时，用户需要等待所有操作处理完成才能注册成功，体验极差。
     2. **使用中间件解耦**：
        - 用户注册 **-> 写入数据库 -> 向中间件发送一条用户注册成功的消息** -> 立即返回注册成功给用户。
        - **此时，用户感知的注册流程已经结束，响应飞快。**
        - 三个独立的系统分别订阅这条消息：
          - 邮件系统：消费消息，发送欢迎邮件
          - 积分系统：消费消息，初始化用户积分
          - 推荐系统，消费消息，在后台获取默认的生成列表。
   - **异步处理的价值：**将串行等待改为并行处理，**缩短主流程响应时间，提升用户体验**，将写完数据再通知改为写了就通知，剩下的慢慢处理。

---

1. **流量削峰**
   - **核心思想：**在面对瞬间的突发流量时，将请求存到消息队列中，让后端系统按照自身能力匀速的消费，避免系统被压垮
     1. **无削峰的传统模式：**
        - 瞬间有10万个请求同时涌入秒杀系统。
        - 系统（如数据库、库存服务）的最大处理能力只有每秒1万次请求。
        - **结果：** 系统瞬间被压垮，数据库连接池占满，所有用户请求超时，活动失败。
     2. **使用中间件削峰后：**
        - 用户的请求，服务器收到之后，首先写入消息队列，加入消息队列长度超过最大值，则直接抛弃用户请求或跳转到错误页面
        - 后端的**库存处理系统**按照自己最大的处理能力（比如每秒1万次），匀速地从中间件中拉取消息，进行真正的库存扣减和订单创建。
        - 处理成功后，再通过其他方式（如WebSocket、推送或页面轮询）通知用户最终结果。
   - **流量削峰的价值：** 将瞬时的、不可控的流量洪峰，转变为平稳的、匀速的流量流。**保护下游脆弱系统不被冲垮，保证系统在高并发下的可用性和稳定性**，即使处理有延迟，也能保证请求不丢失，最终完成处理。



---

### 3.基本概念

本章节介绍 Apache RocketMQ 的基本概念，以便您更好地理解和使用 Apache RocketMQ 。

#### 领域模型

![领域模型](./RocketMQ图床/领域模型.png)

如上图所示，ApacheRocketMQ的消息生命周期主要分为**消息生产者（Message Production**）、**消息存储（Message Storage）**、**消息消费（Message Consumption）**三个部分。

---

#### 生产者（Producer）

生产者是ApacheRocketMQ系统中用来构建并传输消息到服务端的实体，Apache RocketMQ 服务端5.x版本开始，生产者是匿名的，无需管理生产者分组（ProducerGroup）。

在消息生产者中可以定义如下传输行为：

1. **发送方式**：生产者可以通过API接口设置消息发送的方式；例如异步传输或者同步传输
2. **批量发送**：生产者可以通过API接口设置批量消息的传输方式；例如批量发送的消息条数或消息大小
3. **事物消息**：Apache RocketMQ 支持事务消息，对于事务消息需要生产者配合进行事务检查等行为保障事务的最终一致性。

生产者与主题的关系为多对多，即同一个生产者可以向多个主题发送消息，也可以多个生产者对一个主题发送消息。

----

#### 主题(Topic)

Apache RocketMQ中消息传输和储存的顶级容器，用于标识同一业务逻辑的消息。主题通过TopicName来做唯一标识和区分。主题作用如下：

- **定义数据的隔离性：**在ApacheRocketMQ的设计方案中，建议将不同的业务类型的数据拆分到不同主题中管理，通过主题实现储存的隔离性和订阅隔离性。
- **定于数据的身份和权限：**ApacheRocketMQ的消息本身是匿名无身份的，同一分类的消息使用相同的主题来做身份识别和权限管理。

主题是ApacheRocketMQ的顶层容器，所有的消息资源的定义都在主题内完成，但主题是一个逻辑概念，并不是实际消息的容器，主题内部是由多个队列组成，消息的存储和水平扩展能力最终是由队列实现的，并且对主题的所有约束和属性设置也是通过主题内部的队列来实现的。

---

#### 消息队列（QueueMessage）

队列是 Apache RocketMQ 中消息存储和传输的实际容器，也是 Apache RocketMQ 消息的最小存储单元。 Apache RocketMQ 的所有主题都是由多个队列组成，以此实现队列数量的水平拆分和队列内部的流式存储。

队列的主要作用如下：

- **存储顺序性：**队列天然具备顺序性，即消息按照进入队列的顺序写入存储，同一队列间的消息天然存在顺序关系，队列头部为最早写入的消息，队列尾部为最新写入的消息。消息在队列中的位置和消息之间的顺序通过位点（Offset）进行标记管理。
- **流式操作语义：**Apache RocketMQ 基于队列的存储模型可确保消息从任意位点读取任意数量的消息，以此实现类似聚合读取、回溯读取等特性，这些特性是RabbitMQ、ActiveMQ等非队列存储模型不具备的。

消息队列数可在创建主题或变更主题时设置修改，队列数量的设置应遵循少用够用原则，避免随意增加队列数量。

消息队列的读写权限由服务端定义，枚举值如下：

- 6：读写状态，当前队列允许读取消息和写入消息。
- 4：只读状态，当前队列只允许读取消息，不允许写入消息。
- 2：只写状态，当前队列只允许写入消息，不允许读取消息。
- 0：不可读写状态，当前队列不允许读取消息和写入消息。
  - 队列的读写权限属于运维侧操作，不建议频繁修改。

----

#### 消息（Message）

消息是 Apache RocketMQ 中的最小数据传输单元。生产者将业务数据的负载和拓展属性包装成消息发送到 Apache RocketMQ 服务端，服务端按照相关语义将消息投递到消费端进行消费。

Apache RocketMQ 的消息模型具备如下特点：

- **消息不可变性：**消息本质上是已经产生并确定的事件，一旦产生后，消息的内容不会发生改变。即使经过传输链路的控制也不会发生变化，消费端获取的消息都是只读消息视图。

- **消息持久化：**Apache RocketMQ 会默认对消息进行持久化，即将接收到的消息存储到 Apache RocketMQ 服务端的存储文件中，保证消息的可回溯性和系统故障场景下的可恢复性。

Apache RocketMQ 的消息类型如下：

- Normal：[普通消息](https://rocketmq.apache.org/zh/docs/featureBehavior/01normalmessage)，消息本身无特殊语义，消息之间也没有任何关联。
- FIFO：[顺序消息](https://rocketmq.apache.org/zh/docs/featureBehavior/03fifomessage)，Apache RocketMQ 通过消息分组MessageGroup标记一组特定消息的先后顺序，可以保证消息的投递顺序严格按照消息发送时的顺序。
- Delay：[定时/延时消息](https://rocketmq.apache.org/zh/docs/featureBehavior/02delaymessage)，通过指定延时时间控制消息生产后不要立即投递，而是在延时间隔后才对消费者可见。
- Transaction：[事务消息](https://rocketmq.apache.org/zh/docs/featureBehavior/04transactionmessage)，Apache RocketMQ 支持分布式事务消息，支持应用数据库更新和消息调用的事务一致性保障。

其中系统默认的顺序消息和普通消息最大限制4MB，事物消息和延时消息最大限制64MB。

---

#### 订阅关系（Subscription

订阅关系是 Apache RocketMQ 系统中消费者获取消息、处理消息的规则和状态配置。

订阅关系由消费者分组动态注册到服务端系统，并在后续的消息传输中按照订阅关系定义的**过滤规则**进行消息匹配和消费进度维护。

通过配置订阅关系，可控制如下传输行为：

- **消息过滤规则：**用于控制消费者在消费消息时，选择主题内的哪些消息进行消费，设置消费过滤规则可以高效地过滤消费者需要的消息集合，灵活根据不同的业务场景设置不同的消息接收范围。具体信息，请参见[消息过滤](https://rocketmq.apache.org/zh/docs/featureBehavior/07messagefilter)。
- **消费状态：**Apache RocketMQ 服务端默认提供订阅关系持久化的能力，即消费者分组在服务端注册订阅关系后，当消费者离线并再次上线后，可以获取离线前的消费进度并继续消费。

**过滤类型**

定义：消息过滤规则的类型。订阅关系中设置消息过滤规则后，系统将按照过滤规则匹配主题中的消息，只将符合条件的消息投递给消费者消费，实现消息的再次分类。

- TAG过滤：按照Tag字符串进行全文过滤匹配。
- SQL92过滤：按照SQL语法对消息属性进行过滤匹配。
- 过滤表达式：自定义的过滤规则表达式。具体取值规范，请参见[过滤表达式语法规范](https://rocketmq.apache.org/zh/docs/featureBehavior/07messagefilter)。

---

#### 消费者分组（ConsumerGroup）

消费者分组是 Apache RocketMQ 系统中承载多个消费行为一致的消费者的负载均衡分组。

和消费者不同，**消费者分组并不是运行实体，而是一个逻辑资源。**在 Apache RocketMQ 中，通过消费者分组内初始化多个消费者实现消费性能的水平扩展以及高可用容灾。

在消费者分组中，统一定义以下消费行为，同一分组下的多个消费者将按照分组内统一的消费行为和负载均衡策略消费消息。

- **订阅关系：**Apache RocketMQ 以消费者分组的粒度管理订阅关系，实现订阅关系的管理和追溯。具体信息，请参见[订阅关系（Subscription）](https://rocketmq.apache.org/zh/docs/domainModel/09subscription)。
- **投递顺序性**：Apache RocketMQ 的服务端将消息投递给消费者消费时，支持顺序投递和并发投递，投递方式在消费者分组中统一配置。具体信息，请参见[顺序消息](https://rocketmq.apache.org/zh/docs/featureBehavior/03fifomessage)。
- **消费重试策略：** 消费者消费消息失败时的重试策略，包括重试次数、死信队列设置等。具体信息，请参见[消费重试](https://rocketmq.apache.org/zh/docs/featureBehavior/10consumerretrypolicy)。

消费者分组由用户设置并创建，用于区分不同的消费者分组，集群内全局唯一。具体命名规范，请参见[参数限制](https://rocketmq.apache.org/zh/docs/introduction/03limits)。

---

### 4.安装RocketMQ

[官网](https://rocketmq.apache.org/zh/docs/quickStart/02quickstartWithDocker)中介绍了多种安装方式：本地部署RocketMQ、Docker部署RocketMQ、DocketCompose部署Rocket、Kuberenetes部署RocketMQ，本文只使用Docker安装RocketMQ。

#### 1.拉取JDK17

**官方要求JDK1.8以上，请适配自己的项目JDK。**

```shell
 docker pull openjdk:17-jdk
```

#### 2.拉取RocketMQ镜像

```shell
docker pull apache/rocketmq:5.2.0
```

#### 3.创建容器共享网络

RocketMQ 中有多个服务，需要创建多个容器，创建 docker 网络便于容器间相互通信。

```shell
docker network create rocketmq
```

#### 4.启动NameServer

**执行以下命令，我们可以看到 'The Name Server boot success..'， 表示NameServer 已成功启动。**

```shell
docker run -d --name rmqnamesrv -p 9876:9876 --network rocketmq apache/rocketmq:5.2.0 sh mqnamesrv

# 验证 NameServer 是否启动成功
docker logs -f rmqnamesrv
```

#### 5.启动 Broker+Proxy

```shell
# 配置 Broker 的IP地址
echo "brokerIP1=127.0.0.1" > broker.conf
```

---

```shell
# 启动 Broker 和 Proxy
docker run -d --name rmqbroker --network rocketmq -p 10912:10912 -p 10911:10911 -p 10909:10909 -p 8080:8080 -p 8081:8081 -e "NAMESRV_ADDR=rmqnamesrv:9876" -v ./broker.conf:/home/rocketmq/rocketmq-5.2.0/conf/broker.conf apache/rocketmq:5.2.0 sh mqbroker --enable-proxy -c /home/rocketmq/rocketmq-5.2.0/conf/broker.conf
```

**参数说明：**

```shell
docker run -d \  
# 启动容器并在后台运行
```

```shell
# 设置容器名称为 rmqbroker，便于后续管理
--name rmqbroker \
```

```shell
# 将容器连接到名为 rocketmq 的 Docker 网络
# 这样容器可以通过服务名（如 rmqnamesrv）相互通信
--network rocketmq \
```

```shell
# 端口映射（容器端口 -> 宿主机端口）
# 10912:10912 - Broker 的 HA（高可用复制）端口，用于 Master-Slave 同步
# 10911:10911 - 传统 RocketMQ 的主通信端口（客户端/NameServer 与 Broker 通信）
# 10909:10909 - Admin RPC 端口（管理接口、内部通信）
# 8080:8080   - HTTP 访问接口（RocketMQ Dashboard、RESTful API 调试等）
# 8081:8081   - 新版本客户端（RocketMQ 5.x）使用的访问入口，Producer/Consumer 都通过它通信
-p 10912:10912 -p 10911:10911 -p 10909:10909 \
-p 8080:8080 -p 8081:8081 \
```

```shell
# 设置环境变量，指定 NameServer 地址
# rmqnamesrv:9876 表示在 rocketmq 网络中的 rmqnamesrv 容器的 9876 端口
-e "NAMESRV_ADDR=rmqnamesrv:9876" \
```

```shell
# 将宿主机的 broker.conf 文件挂载到容器内指定路径
# 实现配置文件的外部化管理
-v ./broker.conf:/home/rocketmq/rocketmq-5.2.0/conf/broker.conf \
```

```shell
# 使用官方 RocketMQ 5.2.0 镜像
apache/rocketmq:5.2.0 \
```

```shell
# 启动 Broker 并启用 Proxy 功能（RocketMQ 5.0 新架构）
sh mqbroker --enable-proxy \
# 既能被 RocketMQ 客户端用老方式连接（通过 NameServer），也能被新客户端直接用 gRPC 调用（v5.x 推荐方式）。
```

```shell
# 指定 Broker 配置文件路径
-c /home/rocketmq/rocketmq-5.2.0/conf/broker.conf
```

**执行以下命令，我们可以看到 'The broker boot success..'， 表示 Broker 已成功启动。**

```shell
# 验证 Broker 是否启动成功
docker exec -it rmqbroker bash -c "tail -n 10 /home/rocketmq/logs/rocketmqlogs/proxy.log"
```

----

#### 6.停止容器

**逐个执行以下命令,等待返回结果再执行下一条**

```shell
# 停止 NameServer 容器
docker stop rmqnamesrv

# 停止 Broker 容器
docker stop rmqbroker
```

#### 7.重启容器

- 执行下面脚本，再重新执行所有上面的命令！切记。博主使用restart命令启动，再次执行代码无法连接NameServer。
- 有其他办法的请告诉博主，谢谢

```shell
#!/bin/bash
echo "开始清理 RocketMQ 5.2.0（保留镜像）..."

echo "1. 停止并删除容器..."
docker rm -f $(docker ps -a --filter "name=rmq" --format "{{.ID}}") 2>/dev/null || true
docker rm -f $(docker ps -a | grep rocketmq | awk '{print $1}') 2>/dev/null || true

echo "2. 清理网络..."
docker network rm rocketmq 2>/dev/null || true
docker network prune -f

echo "3. 清理数据卷..."
docker volume prune -f

echo "4. 清理配置文件..."
rm -f ~/broker.conf ~/proxy.json ~/broker-simple.conf

echo "RocketMQ 5.2.0 清理完成！镜像已保留。"

# 验证清理结果（不检查镜像）
echo "验证清理结果："
echo "剩余容器: $(docker ps -a | grep -i rocketmq | wc -l)"
echo "剩余网络: $(docker network ls | grep -i rocketmq | wc -l)"
echo "剩余数据卷: $(docker volume ls | grep -i rocketmq | wc -l)"
```



---

### 5.运行官方demo

##### 1.进入broker容器，通过mqadmin创建 Topic。

```shell
docker exec -it rmqbroker bash
sh mqadmin updatetopic -t TestTopic -c DefaultCluster
```

##### 2.创建Springboot项目,导入依赖

```xml
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-client-java</artifactId>
    <version>5.1.0</version>
</dependency>
```

##### 3.消息生产者

Producer对象中还有其他发送消息的方式,请自行查看

```java
package org.example.demo;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientConfigurationBuilder;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author unknown
 * @since 2025/10/29 19:41
 */
public class ProducerExample {

    private static final Logger logger = LoggerFactory.getLogger(ProducerExample.class);

    public static void main(String[] args) throws ClientException {
        // 接入点地址，需要设置成Proxy的地址和端口列表，一般是xxx:8080;xxx:8081
        // 此处为示例，实际使用时请替换为真实的 Proxy 地址和端口
        String endpoint = "localhost:8081";
        // 消息发送的目标Topic名称，需要提前创建。
        String topic = "TestTopic";
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfigurationBuilder builder = ClientConfiguration.newBuilder().setEndpoints(endpoint);
        ClientConfiguration configuration = builder.build();
        // 初始化Producer时需要设置通信配置以及预绑定的Topic。
        Producer producer = provider.newProducerBuilder()
                .setTopics(topic)
                .setClientConfiguration(configuration)
                .build();
        // 普通消息发送。
        Message message = provider.newMessageBuilder()
                .setTopic(topic)
                // 设置消息索引键，可根据关键字精确查找某条消息。
                .setKeys("messageKey")
                // 设置消息Tag，用于消费端根据指定Tag过滤消息。
                .setTag("messageTag")
                // 消息体。
                .setBody("rocketmq-client-java-5.1.0".getBytes())
                .build();
        try {
            // 发送消息，需要关注发送结果，并捕获失败等异常。
            SendReceipt sendReceipt = producer.send(message);
            logger.info("Send message successfully, messageId={}", sendReceipt.getMessageId());
        } catch (ClientException e) {
            logger.error("Failed to send message", e);
        }
        // producer.close();
    }
}
```

##### 4.消息消费者

ClientServiceProvider对象中还有其他方式获取消息,请自行查看

```java
package org.example.demo;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * @author unknown
 * @since 2025/10/29 19:43
 */
public class PushConsumerExample {private static final Logger logger = LoggerFactory.getLogger(PushConsumerExample.class);

    private PushConsumerExample() {
    }

    public static void main(String[] args) throws ClientException, IOException, InterruptedException {
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        // 接入点地址，需要设置成Proxy的地址和端口列表，一般是xxx:8080;xxx:8081
        // 此处为示例，实际使用时请替换为真实的 Proxy 地址和端口
        String endpoints = "localhost:8081";
        ClientConfiguration clientConfiguration = ClientConfiguration.newBuilder()
                .setEndpoints(endpoints)
                .build();
        // 订阅消息的过滤规则，表示订阅所有Tag的消息。
        String tag = "*";
        FilterExpression filterExpression = new FilterExpression(tag, FilterExpressionType.TAG);
        // 为消费者指定所属的消费者分组，Group需要提前创建。
        String consumerGroup = "YourConsumerGroup";
        // 指定需要订阅哪个目标Topic，Topic需要提前创建。
        String topic = "TestTopic";
        // 初始化PushConsumer，需要绑定消费者分组ConsumerGroup、通信参数以及订阅关系。
        PushConsumer pushConsumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(clientConfiguration)
                // 设置消费者分组。
                .setConsumerGroup(consumerGroup)
                // 设置预绑定的订阅关系。
                .setSubscriptionExpressions(Collections.singletonMap(topic, filterExpression))
                // 设置消费监听器。
                .setMessageListener(messageView -> {

                    // 处理消息并返回消费结果。
                    ByteBuffer body = messageView.getBody();
                    String message = StandardCharsets.UTF_8.decode(body).toString();
                    logger.info("Consume message successfully, body={}", message);
                    logger.info("Consume message successfully, messageId={}", messageView.getMessageId());
                    return ConsumeResult.SUCCESS;
                })
                .build();
        Thread.sleep(Long.MAX_VALUE);
        // 如果不需要再使用 PushConsumer，可关闭该实例。
        // pushConsumer.close();
    }
}
```

##### 5.控制台输出

```shell
# 消息生产者
21:10:08.556 [main] INFO org.example.demo.ProducerExample -- Send message successfully, messageId=010C9A3CF97EB8060C0913AC3000000000

# 消息消费者
21:10:39.940 [RocketmqMessageConsumption-0-17] INFO org.example.demo.PushConsumerExample -- Consume message successfully, body=rocketmq-client-java-5.1.0
21:10:39.946 [RocketmqMessageConsumption-0-17] INFO org.example.demo.PushConsumerExample -- Consume message successfully, messageId=010C9A3CF97EB8060C0913AC3000000000
```



----

### 6.安装mqadmin

`mqadmin` 是 RocketMQ 的一个强大的命令行工具，提供了一系列用于管理和监控消息队列的命令。



---

## Springboot中集成RocketMQ

本项目是由三个模块组成，Producer、SimpleConsumer、PushConsumer。

### 1.项目配置

1. 创建Springboot项目并引入依赖

   ```xml
   <dependency>
       <groupId>org.apache.rocketmq</groupId>
       <artifactId>rocketmq-v5-client-spring-boot-starter</artifactId>
       <version>2.3.0</version>
   </dependency>
   ```

2. 消息生产者配置applocation.yml

   - **endpoints: 127.0.0.1:8081** :指向Broker端口或者Proxy端口
     - 在RocketMQ5中，应该指向Proxy端口

   ```yml
   server:
     port: 10001
     # 消息生产者配置
   rocketmq:
     producer:
       endpoints: 127.0.0.1:8081
   ```

3. Simple消费者配置applocation.yml

   ```yml
   server:
     port: 10002
   rocketmq:
     simple-consumer:
       endpoints: 127.0.0.1:8081
       consumer-group: demo-group
       topic: spring-boot-normal-topic
       tag: '*'
       request-timeout: 300
   ```

4. Push消费者配置applocation.yml

   ```yml
   # 配置 push consumer 连接 proxy
   rocketmq:
     push-consumer:
       endpoints: 127.0.0.1:8081
       tag: '*'
   ```

5. 进入docker容器，创建主题

   - **-t spring-boot-normal-topic**：创建spring-boot-normal-topic的主题
   - **-c DefaultCluster**：指定集群名称，在RocketMQ架构中，`DefaultCluster` 是RocketMQ安装后默认创建的集群名称。

   ```shell
   # 进入docker容器
   docker exec -it rmqbroker bash
   # 创建主题
   sh mqadmin updatetopic -t spring-boot-normal-topic -c DefaultCluster
   ```

---

### 2.基础消息

#### 消息生产者代码

```java
@SpringBootApplication
public class RocketMQProducerApplication implements CommandLineRunner {
    public static void main(String[] args) {
        SpringApplication.run(RocketMQProducerApplication.class, args);
    }

    @Resource
    private RocketMQClientTemplate rocketMQClientTemplate;

    // 实现CommandLineRunner接口,实现润,该方法会在项目启动时运行一次
    @Override
    public void run(String... args) throws Exception {

        // 发送普通类型的消息
        sendNormalMessage();
    }

    private void sendNormalMessage() throws ExecutionException, InterruptedException {
        String normalTopic = "spring-boot-normal-topic";
        // 同步发送对象类型消息
        SendReceipt objectSendReceipt = rocketMQClientTemplate.syncSendNormalMessage(normalTopic,
                new UserMessage(18, "陈", (byte) 19));
        System.out.println("普通发送主题,对象消息: "+normalTopic+" 消息内容= "+objectSendReceipt);

        // 同步发送字符串类型消息
        SendReceipt stringSendReceipt = rocketMQClientTemplate.syncSendNormalMessage(normalTopic,
               "字符串类型消息测试");
        System.out.println("普通发送主题,字符串消息: "+normalTopic+" 消息内容= "+stringSendReceipt);

        // 同步发送字节类型消息
        SendReceipt byteSendReceipt = rocketMQClientTemplate.syncSendNormalMessage(normalTopic,
                "字节消息".getBytes(StandardCharsets.UTF_8));
        System.out.println("普通发送主题,字节消息: "+normalTopic+" 消息内容= "+byteSendReceipt);

        // 通过 Spring 发送消息
        SendReceipt SpringSendReceipt = rocketMQClientTemplate.syncSendNormalMessage(normalTopic, MessageBuilder.
                withPayload("Spring方式的消息".getBytes()).build());
        System.out.println("普通发送主题,Spring消息: "+normalTopic+" 消息内容= "+SpringSendReceipt);

        //发送基于标签的消息  参数 1: destination 目的地 topic:*
        SendReceipt labelSendReceipt = rocketMQClientTemplate.syncSendNormalMessage(normalTopic + ":Logs",
                "标签: Logs");
        System.out.println("普通发送主题,标签消息: "+normalTopic+" 消息内容= "+labelSendReceipt);

        //发送异步消息
        CompletableFuture<SendReceipt> completableFuture = rocketMQClientTemplate.asyncSendNormalMessage(normalTopic,
                "异步消息", new CompletableFuture<>());
        System.out.println(completableFuture.get().getMessageId());

        //兼容老版本的 API
        rocketMQClientTemplate.convertAndSend(normalTopic+":news","rocketMQTemplate message");
    }

    private class UserMessage {

        private int id ;

        private String name;

        private Byte age;

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Byte getAge() {
            return age;
        }

        public void setAge(Byte age) {
            this.age = age;
        }

        public UserMessage(int id, String name, Byte age) {
            this.id = id;
            this.name = name;
            this.age = age;
        }
    }
}

```

#### 消息消费者代码

##### SimpleConsumer

```java
@SpringBootApplication
public class RocketMQSimpleApplication implements CommandLineRunner {
    public static void main(String[] args) {
        SpringApplication.run(RocketMQSimpleApplication.class, args);
    }

    @Autowired
    private RocketMQClientTemplate rocketMQClientTemplate;

    // 实现CommandLineRunner接口,实现润,该方法会在项目启动时运行一次
    @Override
    public void run(String... args) throws Exception {
        for (int i = 0; i < 10; i++) {
            // 参数maxMessageNum: 最大消费数,
            // Duration.ofSeconds(60):如果有可用的消息，则此方法会立即返回。否则，它将等待传递的超时。如果超时到期，将返回一个空映射
            List<MessageView> messageList = rocketMQClientTemplate.receive(10, Duration.ofSeconds(60));
            System.out.println(messageList.size());
            messageList.forEach(messageView -> {
                System.out.println(StandardCharsets.UTF_8.decode(messageView.getBody()));
            });
        }
    }
}
```

##### PushConsumer

```java
@Service
@RocketMQMessageListener(consumerGroup="demo-group",
        topic="spring-boot-normal-topic",filterExpressionType= "tag",tag = "*")
@SpringBootApplication
public class RocketMQPushApplication implements RocketMQListener {
    public static void main(String[] args) {
        SpringApplication.run(RocketMQPushApplication.class, args);
    }

    @Override
    public ConsumeResult consume(MessageView messageView) {
        System.out.println("received message: " + messageView);
        //获取消息体
        ByteBuffer byteBuffer = messageView.getBody();
        System.out.println(StandardCharsets.UTF_8.decode(byteBuffer));
        return ConsumeResult.SUCCESS;
    }
}
```

---

### 3.定时/延时消息

- 创建**定时/延时消息**主题

  - ```shell
    # 进入docker容器
    docker exec -it rmqbroker bash
    # 创建主题
    sh mqadmin updatetopic -t spring-boot-delay-topic -c DefaultCluster -a +message.type=DELAY
    
    # 一般是需要指定NameServer的地址: -n 127.0.0.1:9876,但我们是docker部署,按照官网来就好,如下仅供参考:
    sh mqadmin updateTopic -c DefaultCluster -t spring-boot-delay-topic -n 127.0.0.1:9876 -a +message.type=DELAY

- 同步RocketMQ服务器时间 `ntpdate time.windows.com `，一般线上服务器不会出现这个问题，但是本机部署可能会。

#### 消息生产者代码

```java
@SpringBootApplication
public class RocketMQProducerApplication implements CommandLineRunner {
    public static void main(String[] args) {
        SpringApplication.run(RocketMQProducerApplication.class, args);
    }
    
    @Resource
    private RocketMQClientTemplate rocketMQClientTemplate;

    // 实现CommandLineRunner接口,实现润,该方法会在项目启动时运行一次
    @Override
    public void run(String... args) throws Exception {

        // 发送定时|延时消息
        sendDelayMessage();
    }

    //发送延时|定时消息
    public void sendDelayMessage(){
        String delayTopic = "spring-boot-delay-topic";

        //发送一个对象类型消息 延时 10s
        SendReceipt sendReceipt = rocketMQClientTemplate.syncSendDelayMessage(delayTopic, new UserMessage()
                .setId(1).setUserName("name").setUserAge((byte) 3), Duration.ofSeconds(10));
        System.out.printf("delaySend to topic %s sendReceipt=%s %n", delayTopic, sendReceipt);

        //发送字节类型消息 延时 30s
        sendReceipt = rocketMQClientTemplate.syncSendDelayMessage(delayTopic, MessageBuilder.
                withPayload("test message".getBytes()).build(), Duration.ofSeconds(30));
        System.out.printf("delaySend to topic %s sendReceipt=%s %n", delayTopic, sendReceipt);

        //发送字符串类型消息  延时 60s
        sendReceipt = rocketMQClientTemplate.syncSendDelayMessage(delayTopic, "this is my message",
                Duration.ofSeconds(60));
        System.out.printf("delaySend to topic %s sendReceipt=%s %n", delayTopic, sendReceipt);

        //发送字节类型消息 延时 90s
        sendReceipt = rocketMQClientTemplate.syncSendDelayMessage(delayTopic, "byte messages".getBytes(StandardCharsets.UTF_8),
                Duration.ofSeconds(90));
        System.out.printf("delaySend to topic %s sendReceipt=%s %n", delayTopic, sendReceipt);
    }
}
```

#### 消息消费者代码

##### SimpleConsumer

- 与**基础消息**的SimpleConsumer不同的是，在**定时/延时消息**SimpleConsumer中,修改了配置文件中消费者分组`consumerGroup`和主题`topic`，代码没有什么变化。

  ```yml
  server:
    port: 1003
  rocketmq:
    simple-consumer:
      endpoints: 127.0.0.1:8081
      consumer-group: demo-delay-group
      topic: spring-boot-delay-topic
      tag: '*'
      request-timeout: 300
  ```

```java
@SpringBootApplication
public class RocketMQSimpleApplication implements CommandLineRunner {
    public static void main(String[] args) {
        SpringApplication.run(RocketMQSimpleApplication.class, args);
    }

    @Autowired
    private RocketMQClientTemplate rocketMQClientTemplate;

    // 实现CommandLineRunner接口,实现润,该方法会在项目启动时运行一次
    @Override
    public void run(String... args) throws Exception {
        for (int i = 0; i < 10; i++) {
            // 参数maxMessageNum: 最大消费数,
            // Duration.ofSeconds(60):如果有可用的消息，则此方法会立即返回。否则，它将等待传递的超时。如果超时到期，将返回一个空映射
            List<MessageView> messageList = rocketMQClientTemplate.receive(10, Duration.ofSeconds(60));
            System.out.println(messageList.size());
            messageList.forEach(messageView -> {
                System.out.println("消息ID :"+messageView.getMessageId()
                        +" 消息内容: "+StandardCharsets.UTF_8.decode(messageView.getBody()));
            });
        }
    }
}
```

##### PushConsumer

- 与**基础消息**的PushConsumer不同的是，在**定时/演示消息**PushConsumer中，修改了消费者分组`consumerGroup`和主题`topic`

```java
@Service
@RocketMQMessageListener(consumerGroup="demo-delay-group",
        topic="spring-boot-delay-topic",filterExpressionType= "tag",tag = "*")
@SpringBootApplication
public class RocketMQPushApplication implements RocketMQListener {
    public static void main(String[] args) {
        SpringApplication.run(RocketMQPushApplication.class, args);
    }

    @Override
    public ConsumeResult consume(MessageView messageView) {
        System.out.println("received message: " + messageView);
        //获取消息体
        ByteBuffer byteBuffer = messageView.getBody();
        System.out.println(StandardCharsets.UTF_8.decode(byteBuffer));
        return ConsumeResult.SUCCESS;
    }
}
```

---

### 4.发送顺序消息

- 创建**顺序消息**主题与**顺序消费者组**

  ```shell
  # 进入docker容器
  docker exec -it rmqbroker bash
  # 创建主题
  sh mqadmin updatetopic -t spring-boot-fifo-topic -c DefaultCluster -a +message.type=FIFO
  # 创建顺序消费者组
  sh mqadmin updateSubGroup -c DefaultCluster -g spring-boot-fifo-group -o true
  
  # 一般是需要指定NameServer的地址: -n 127.0.0.1:9876,但我们是docker部署,按照官网来就好,如下仅供参考:
  sh mqadmin updateTopic -c DefaultCluster -t spring-boot-fifo-topic -n 127.0.0.1:9876 -a +message.type=DELAY
  sh mqadmin updateSubGroup -c DefaultCluster -g spring-boot-fifo-group -n 127.0.0.1:9876 -o true
  ```

#### 消息生产者代码

- 代码中的UserMessage对象请参看基础消息中的UserMessage对象，或自行创建。

```java
@SpringBootApplication
public class RocketMQProducerApplication implements CommandLineRunner {
    public static void main(String[] args) {
        SpringApplication.run(RocketMQProducerApplication.class, args);
    }

    @Resource
    private RocketMQClientTemplate rocketMQClientTemplate;

    // 实现CommandLineRunner接口,实现润,该方法会在项目启动时运行一次
    @Override
    public void run(String... args) throws Exception {
        
        // 发送顺序消息
        sendFIFOMessage();
    }

    // 顺序类型消息
    public void sendFIFOMessage() {
        String fifoTopic = "spring-boot-fifo-topic";
        String messageGroup = "spring-boot-fifo-group";

        //发送对象类型顺序消息
        SendReceipt objectSendReceipt = rocketMQClientTemplate.syncSendFifoMessage(fifoTopic,
                new UserMessage(18, "陈", (byte) 19), messageGroup);
        System.out.println("发送顺序消息主题-对象消息: "+fifoTopic+" 消息内容= "+objectSendReceipt);

        //发送字节类型顺序消息 spring
        SendReceipt springByteSendReceipt = rocketMQClientTemplate.syncSendFifoMessage(fifoTopic, MessageBuilder.
                withPayload("Spring字节类型顺序消息".getBytes()).build(), messageGroup);
        System.out.println("发送顺序消息主题-spring字节类型: "+fifoTopic+" 消息内容= "+springByteSendReceipt+" 消费者分组: "+messageGroup);

        //发送字符串类型顺序消息
        SendReceipt stringSendReceipt = rocketMQClientTemplate.syncSendFifoMessage(fifoTopic,
                "字符串顺序消息", messageGroup);
        System.out.println("发送顺序消息主题-字符串类型: "+fifoTopic+" 消息内容= "+stringSendReceipt+" 消费者分组: "+messageGroup);

        //发送字节类型顺序消息 rocketmq
        SendReceipt rocketMQByteSendReceipt = rocketMQClientTemplate.syncSendFifoMessage(fifoTopic,
                "RocketMQ字节顺序消息".getBytes(StandardCharsets.UTF_8), messageGroup);
        System.out.println("发送顺序消息主题-rocketmq字节类型: "+fifoTopic+" 消息内容= "+rocketMQByteSendReceipt+" 消费者分组: "+messageGroup);
    }
}
```

#### 消息消费者代码

##### PushConsumer

- 与**定时/延时消息**的PushConsumer不同的是，在**顺序消息**PushConsumer中，修改了消费者分组`consumerGroup`和主题`topic`，代码没有什么变化

```java
@Service
@RocketMQMessageListener(consumerGroup="spring-boot-fifo-group",
        topic="spring-boot-fifo-topic",filterExpressionType= "tag",tag = "*")
@SpringBootApplication
public class RocketMQPushApplication implements RocketMQListener {
    public static void main(String[] args) {
        SpringApplication.run(RocketMQPushApplication.class, args);
    }

    @Override
    public ConsumeResult consume(MessageView messageView) {
        System.out.println("received message: " + messageView);
        //获取消息体
        ByteBuffer byteBuffer = messageView.getBody();
        System.out.println(StandardCharsets.UTF_8.decode(byteBuffer));
        return ConsumeResult.SUCCESS;
    }
}
```

##### SimpleConsumer

- 与**定时/延时消息**的SimpleConsumer不同的是，在**顺序消息**SimpleConsumer中,修改了配置文件中消费者分组`consumerGroup`和主题`topic`，代码没有什么变化。

  ```yml
  rocketmq:
    simple-consumer:
      endpoints: 127.0.0.1:8081
      consumer-group: spring-boot-fifo-group
      topic: spring-boot-fifo-topic
      tag: '*'
      request-timeout: 300
  ```

```java
@SpringBootApplication
public class RocketMQSimpleApplication implements CommandLineRunner {
    public static void main(String[] args) {
        SpringApplication.run(RocketMQSimpleApplication.class, args);
    }

    @Autowired
    private RocketMQClientTemplate rocketMQClientTemplate;

    // 实现CommandLineRunner接口,实现润,该方法会在项目启动时运行一次
    @Override
    public void run(String... args) throws Exception {
        for (int i = 0; i < 10; i++) {
            // 参数maxMessageNum: 最大消费数,
            // Duration.ofSeconds(60):如果有可用的消息，则此方法会立即返回。否则，它将等待传递的超时。如果超时到期，将返回一个空映射
            List<MessageView> messageList = rocketMQClientTemplate.receive(10, Duration.ofSeconds(60));
            System.out.println(messageList.size());
            messageList.forEach(messageView -> {
                System.out.println("消息ID :"+messageView.getMessageId()
                        +" 消息内容: "+StandardCharsets.UTF_8.decode(messageView.getBody()));
            });
        }
    }
}
```

---

### 5.发送事务消息

- 创建事务类型主题

  ```shell
  # 进入docker容器
  docker exec -it rmqbroker bash
  
  # 创建事务主题
  sh mqadmin updateTopic  -c DefaultCluster  -t spring-boot-trans-topic -a +message.type=TRANSACTION
  
  # 一般是需要指定NameServer的地址: -n 127.0.0.1:9876,但我们是docker部署,按照官网来就好,如下仅供参考:
  sh mqadmin updateTopic  -c DefaultCluster  -t spring-boot-trans-topic -n 127.0.0.1:9876 -a +message.type=TRANSACTION
  ```

#### 消息生产者代码

```java
@SpringBootApplication
public class RocketMQProducerApplication implements CommandLineRunner {
    public static void main(String[] args) {
        SpringApplication.run(RocketMQProducerApplication.class, args);
    }

    @Resource
    private RocketMQClientTemplate rocketMQClientTemplate;

    // 实现CommandLineRunner接口,实现润,该方法会在项目启动时运行一次
    @Override
    public void run(String... args) throws Exception {

        // 发送事务消息
        sendTransactionMessage();
    }

    private static final Logger logger = LoggerFactory.getLogger(RocketMQProducerApplication.class);
    //发送事务消息
    void sendTransactionMessage() throws ClientException {
        String transTopic = "spring-boot-trans-topic";
        Pair<SendReceipt, Transaction> pair;
        SendReceipt sendReceipt;
        try {
            //发送消息  rocketmq  对于消费者不可见
            pair = rocketMQClientTemplate.sendMessageInTransaction(transTopic,
                    MessageBuilder.withPayload( new UserMessage(18, "陈", (byte) 19))
                            .setHeader("OrderId", 1).build());
        } catch (ClientException e) {
            throw new RuntimeException(e);
        }
        sendReceipt = pair.getSendReceipt();
        System.out.printf("transactionSend to topic %s sendReceipt=%s %n", transTopic, sendReceipt);
        Transaction transaction = pair.getTransaction();
        // executed local transaction
        if (doLocalTransaction(1)) {
            transaction.commit();
        } else {
            transaction.rollback();
        }
    }


    //当 rocketmq 始终没有接收到本地事务状态 此时服务端的消息的状态为 UNKNOWN
    // 当消息为这种状态是 rocketmq 会进行会查,使用消息的业务 id 举例: orderId
    //本地数据库中存在 Orderid 本地事务提交
    @RocketMQTransactionListener
    static class TransactionListenerImpl implements RocketMQTransactionChecker {
        @Override
        public TransactionResolution check(MessageView messageView) {
            if (Objects.nonNull(messageView.getProperties().get("OrderId"))) {
                logger.info("Receive transactional message check, message={}", messageView);
                return TransactionResolution.COMMIT;
            }
            logger.info("rollback transaction");
            return TransactionResolution.ROLLBACK;
        }
    }

    //模拟本地事务
    boolean doLocalTransaction(int number) {
        logger.info("execute local transaction");
        return number > 0;
    }
}
```

#### 消息消费者代码

##### PushConsumer

- 与**顺序消息**的PushConsumer不同的是，在**事务消息**PushConsumer中，修改了消费者分组`consumerGroup`和主题`topic`，代码没有什么变化

```java
@Service
@RocketMQMessageListener(consumerGroup="demo-trans-group",
        topic="spring-boot-trans-topic",filterExpressionType= "tag",tag = "*")
@SpringBootApplication
public class RocketMQPushApplication implements RocketMQListener {
    public static void main(String[] args) {
        SpringApplication.run(RocketMQPushApplication.class, args);
    }

    @Override
    public ConsumeResult consume(MessageView messageView) {
        System.out.println("received message: " + messageView);
        //获取消息体
        ByteBuffer byteBuffer = messageView.getBody();
        System.out.println(StandardCharsets.UTF_8.decode(byteBuffer));
        return ConsumeResult.SUCCESS;
    }
}
```

##### SimpleConsumer

- 与**顺序**的SimpleConsumer不同的是，在**事务消息**SimpleConsumer中,修改了配置文件中消费者分组`consumerGroup`和主题`topic`，代码没有什么变化。

  ```yml
  server:
    port: 1003
  rocketmq:
    simple-consumer:
      endpoints: 127.0.0.1:8081
      consumer-group: demo-trans-group
      topic: spring-boot-trans-topic
      tag: '*'
      request-timeout: 300
  ```

```java
@SpringBootApplication
public class RocketMQSimpleApplication implements CommandLineRunner {
    public static void main(String[] args) {
        SpringApplication.run(RocketMQSimpleApplication.class, args);
    }

    @Autowired
    private RocketMQClientTemplate rocketMQClientTemplate;

    // 实现CommandLineRunner接口,实现润,该方法会在项目启动时运行一次
    @Override
    public void run(String... args) throws Exception {
        for (int i = 0; i < 10; i++) {
            // 参数maxMessageNum: 最大消费数,
            // Duration.ofSeconds(60):如果有可用的消息，则此方法会立即返回。否则，它将等待传递的超时。如果超时到期，将返回一个空映射
            List<MessageView> messageList = rocketMQClientTemplate.receive(10, Duration.ofSeconds(60));
            System.out.println(messageList.size());
            messageList.forEach(messageView -> {
                System.out.println("消息ID :"+messageView.getMessageId()
                        +" 消息内容: "+StandardCharsets.UTF_8.decode(messageView.getBody()));
            });
        }
    }
}
```

---

## 集群搭建



## 源码学习



