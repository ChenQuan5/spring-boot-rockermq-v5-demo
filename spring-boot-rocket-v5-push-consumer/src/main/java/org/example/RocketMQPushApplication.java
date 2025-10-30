package org.example;

import org.apache.rocketmq.client.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.core.RocketMQListener;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * @author unknown
 * @since 2025/10/28 21:56
 */
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
