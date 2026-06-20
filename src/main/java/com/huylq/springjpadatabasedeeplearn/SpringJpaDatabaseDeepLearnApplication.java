package com.huylq.springjpadatabasedeeplearn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SpringJpaDatabaseDeepLearnApplication {

  public static void main(String[] args) {
    SpringApplication.run(SpringJpaDatabaseDeepLearnApplication.class, args);
  }

}
