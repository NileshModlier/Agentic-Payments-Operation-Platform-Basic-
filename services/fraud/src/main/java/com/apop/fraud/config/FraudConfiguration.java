package com.apop.fraud.config;

import com.apop.fraud.messaging.FraudMessagingProperties;
import com.apop.fraud.redis.RedisWindows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(FraudMessagingProperties.class)
public class FraudConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    String fraudPepper(@Value("${apop.pepper}") String pepper) {
        return pepper;
    }

    @Bean
    RedisWindows redisWindows(StringRedisTemplate redis) {
        return new RedisWindows(redis);
    }
}
