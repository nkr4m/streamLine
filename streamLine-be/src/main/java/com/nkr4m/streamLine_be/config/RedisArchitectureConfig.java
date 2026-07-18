package com.nkr4m.streamLine_be.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Configuration
public class RedisArchitectureConfig {

    private final StringRedisTemplate redisTemplate;
    private final ChatWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String STREAM_KEY = "stream:messages:ingest";
    private static final String CONSUMER_GROUP = "streamline-group";
    private static final String PUBSUB_CHANNEL = "ws:notifications";

    public RedisArchitectureConfig(StringRedisTemplate redisTemplate, ChatWebSocketHandler webSocketHandler) {
        this.redisTemplate = redisTemplate;
        this.webSocketHandler = webSocketHandler;
    }

    @PostConstruct
    public void initStream() {
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);
        } catch (Exception ignored) { }
    }

    // --- STREAM LISTENER (The Broadcaster) ---
    @Bean
    public Subscription streamSubscription(RedisConnectionFactory factory) {
        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder().pollTimeout(Duration.ofMillis(100)).build();

        var container = StreamMessageListenerContainer.create(factory, options);
        String consumerName = UUID.randomUUID().toString();

        StreamListener<String, MapRecord<String, String, String>> streamListener = message -> {
            try {
                var payload = message.getValue();
                System.out.println("[Stream] Processing message: " + payload);
                
                // 1. Persist to DB here...
                
                // 2. Publish to Pub/Sub for routing
                String jsonMessage = objectMapper.writeValueAsString(payload);
                redisTemplate.convertAndSend(PUBSUB_CHANNEL, jsonMessage);

                // 3. Acknowledge stream message
                redisTemplate.opsForStream().acknowledge(STREAM_KEY, CONSUMER_GROUP, message.getId());
                
             // 4. Delete the message entirely to free space
                redisTemplate.opsForStream().delete(STREAM_KEY, message.getId());
            } catch (Exception e) {
                e.printStackTrace();
            }
        };

        Subscription subscription = container.receive(
                Consumer.from(CONSUMER_GROUP, consumerName),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()),
                streamListener
        );
        container.start();
        return subscription;
    }

    // --- PUB/SUB LISTENER (The Router) ---
    @Bean
    public RedisMessageListenerContainer pubSubContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);

        MessageListener pubSubListener = (Message message, byte[] pattern) -> {
            try {
                String body = new String(message.getBody());
                System.out.println("[PubSub] Received broadcast: " + body);
                
                Map<String, String> data = objectMapper.readValue(body, Map.class);
                String receiverId = data.get("receiver");

                // Send to socket ONLY if this instance holds the connection
                webSocketHandler.sendMessageToUser(receiverId, body);
            } catch (Exception e) {
                e.printStackTrace();
            }
        };

        container.addMessageListener(pubSubListener, new ChannelTopic(PUBSUB_CHANNEL));
        return container;
    }
}
