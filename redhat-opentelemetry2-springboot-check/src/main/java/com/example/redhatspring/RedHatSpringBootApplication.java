package com.example.redhatspring;

import java.util.concurrent.CountDownLatch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class RedHatSpringBootApplication {

  public static void main(String[] args) {
    SpringApplication.run(RedHatSpringBootApplication.class, args);
  }

  @Bean
  CountDownLatch asyncCompletedLatch(@Value("${app.expected-count:1}") long expectedCount) {
    return new CountDownLatch((int) expectedCount);
  }
}
