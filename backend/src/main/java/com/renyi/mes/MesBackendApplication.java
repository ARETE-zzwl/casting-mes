package com.renyi.mes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class MesBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(MesBackendApplication.class, args);
	}

}
