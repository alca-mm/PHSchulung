package de.internal.awareness;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PhishingAwarenessTrainerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PhishingAwarenessTrainerApplication.class, args);
    }

}
