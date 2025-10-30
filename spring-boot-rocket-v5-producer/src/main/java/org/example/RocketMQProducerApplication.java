package org.example;

import jakarta.annotation.Resource;
import org.apache.rocketmq.client.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.apis.producer.Transaction;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.apache.rocketmq.client.common.Pair;
import org.apache.rocketmq.client.core.RocketMQClientTemplate;
import org.apache.rocketmq.client.core.RocketMQTransactionChecker;
import org.apache.rocketmq.client.java.example.PushConsumerExample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * @author unknown
 * @since 2025/10/28 21:24
 */
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
//        sendNormalMessage();

        // 发送定时|延时消息
//        sendDelayMessage();

        // 发送顺序消息
//        sendFIFOMessage();

        // 发送事务消息
        sendTransactionMessage();
    }

    // 普通类型消息
    private void sendNormalMessage() throws ExecutionException, InterruptedException {
        String normalTopic = "spring-boot-normal-topic";
        // 同步发送对象类型消息
        SendReceipt objectSendReceipt = rocketMQClientTemplate.syncSendNormalMessage(normalTopic,
                new UserMessage(18, "陈", (byte) 19));
        System.out.println("发送普通消息主题,对象消息: "+normalTopic+" 消息内容= "+objectSendReceipt);

        // 同步发送字符串类型消息
        SendReceipt stringSendReceipt = rocketMQClientTemplate.syncSendNormalMessage(normalTopic,
               "字符串类型消息测试");
        System.out.println("发送普通消息主题,字符串消息: "+normalTopic+" 消息内容= "+stringSendReceipt);

        // 同步发送字节类型消息
        SendReceipt byteSendReceipt = rocketMQClientTemplate.syncSendNormalMessage(normalTopic,
                "字节消息".getBytes(StandardCharsets.UTF_8));
        System.out.println("发送普通消息主题,字节消息: "+normalTopic+" 消息内容= "+byteSendReceipt);

        // 通过 Spring 发送消息
        SendReceipt SpringSendReceipt = rocketMQClientTemplate.syncSendNormalMessage(normalTopic, MessageBuilder.
                withPayload("Spring方式的消息".getBytes()).build());
        System.out.println("发送普通消息主题,Spring消息: "+normalTopic+" 消息内容= "+SpringSendReceipt);

        //发送基于标签的消息  参数 1: destination 目的地 topic:*
        SendReceipt labelSendReceipt = rocketMQClientTemplate.syncSendNormalMessage(normalTopic + ":Logs",
                "标签: Logs");
        System.out.println("发送普通消息主题,标签消息: "+normalTopic+" 消息内容= "+labelSendReceipt);

        //发送异步消息
        CompletableFuture<SendReceipt> completableFuture = rocketMQClientTemplate.asyncSendNormalMessage(normalTopic,
                "异步消息", new CompletableFuture<>());
        System.out.println(completableFuture.get().getMessageId());

        //兼容老版本的 API
        rocketMQClientTemplate.convertAndSend(normalTopic+":news","rocketMQTemplate message");
    }

    // 发送延时|定时消息
    public void sendDelayMessage(){
        String delayTopic = "spring-boot-delay-topic";

        //发送一个对象类型消息 延时 10s
        SendReceipt objectSendReceipt = rocketMQClientTemplate.syncSendDelayMessage(delayTopic,
        new UserMessage(18, "陈", (byte) 19), Duration.ofSeconds(10));
        System.out.println("发送定时|延时消息主题,对象消息: "+delayTopic+" 消息内容= "+objectSendReceipt);

        //发送字节类型消息 延时 30s
        SendReceipt byteSendReceipt = rocketMQClientTemplate.syncSendDelayMessage(delayTopic, MessageBuilder.
                withPayload("test message".getBytes()).build(), Duration.ofSeconds(30));
        System.out.println("发送定时|延时消息主题,字节消息: "+delayTopic+" 消息内容= "+byteSendReceipt);

        //发送字符串类型消息  延时 60s
        SendReceipt stringSendReceipt = rocketMQClientTemplate.syncSendDelayMessage(delayTopic,
                "this is my message", Duration.ofSeconds(60));
        System.out.println("发送定时|延时消息主题,字符串消息: "+delayTopic+" 消息内容= "+stringSendReceipt);

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
