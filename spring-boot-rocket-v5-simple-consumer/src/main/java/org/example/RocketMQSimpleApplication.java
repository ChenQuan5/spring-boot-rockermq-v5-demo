package org.example;

import jakarta.annotation.Resource;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.core.RocketMQClientTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * @author unknown
 * @since 2025/10/28 21:58
 */
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
