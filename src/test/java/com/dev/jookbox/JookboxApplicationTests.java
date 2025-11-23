package com.dev.jookbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JookboxApplicationTests {

	@Test
	void contextLoads() {
	}

}
