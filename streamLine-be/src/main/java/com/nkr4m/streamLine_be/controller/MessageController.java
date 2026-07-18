package com.nkr4m.streamLine_be.controller;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final StringRedisTemplate redisTemplate;

    public MessageController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostMapping
    public String sendMessage(@RequestBody Map<String, String> request) {
        Map<String, String> messageData = new HashMap<>();
        messageData.put("id", java.util.UUID.randomUUID().toString());
        messageData.put("sender", request.get("sender"));
        messageData.put("receiver", request.get("receiver"));
        messageData.put("content", request.get("content"));

        // Push directly to the ingest stream
        redisTemplate.opsForStream().add("stream:messages:ingest", messageData);
        return "Message queued for delivery";
    }
}
