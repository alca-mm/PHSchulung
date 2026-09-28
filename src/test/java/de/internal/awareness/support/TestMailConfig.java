package de.internal.awareness.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test-Konfiguration, die einen {@link RecordingJavaMailSender} als vorrangigen {@code JavaMailSender}
 * bereitstellt. So laufen Versandtests ohne echtes SMTP: Nachrichten werden nur aufgezeichnet.
 *
 * <p>Verwendung: {@code @Import(TestMailConfig.class)} an der Testklasse; den Recorder per
 * {@code @Autowired} beziehen und zwischen Testmethoden {@link RecordingJavaMailSender#reset()} aufrufen.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestMailConfig {

    @Bean
    @Primary
    public RecordingJavaMailSender recordingJavaMailSender() {
        return new RecordingJavaMailSender();
    }
}
