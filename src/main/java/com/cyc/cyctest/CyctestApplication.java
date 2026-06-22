package com.cyc.cyctest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CyctestApplication {

	public static void main(String[] args) {
		SpringApplication.run(CyctestApplication.class, args);
	}

}
