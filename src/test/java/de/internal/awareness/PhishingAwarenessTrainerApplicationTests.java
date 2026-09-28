package de.internal.awareness;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PhishingAwarenessTrainerApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
    }

    @Test
    void applicationBeanIsPresent() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.getBean(PhishingAwarenessTrainerApplication.class)).isNotNull();
    }

}
