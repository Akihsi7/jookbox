package com.dev.jookbox;

import org.springframework.boot.SpringApplication;

public class TestJookboxApplication {

	public static void main(String[] args) {
		SpringApplication.from(JookboxApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
