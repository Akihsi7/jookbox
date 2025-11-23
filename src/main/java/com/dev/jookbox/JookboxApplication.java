package com.dev.jookbox;

import com.dev.jookbox.config.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
public class JookboxApplication {

	public static void main(String[] args) {
		SpringApplication.run(JookboxApplication.class, args);
	}

}
