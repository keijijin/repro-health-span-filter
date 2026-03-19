package com.example.communityspring;

import java.util.concurrent.CountDownLatch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class CommunitySpringBootApplication {

  public static void main(String[] args) {
    SpringApplication.run(CommunitySpringBootApplication.class, args);
  }

  @Bean
  CountDownLatch asyncCompletedLatch(@Value("${app.expected-count:1}") long expectedCount) {
    return new CountDownLatch((int) expectedCount);
  }
}
