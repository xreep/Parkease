package com.smartparking.email;

import com.smartparking.common.seed.DemoMode;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.StringUtils;

@Slf4j
@Configuration
public class EmailConfig {

    @Bean
    EmailSender emailSender(AppMailProperties properties, DemoMode demoMode) {
        EmailSender sender = realSender(properties);
        if (demoMode.enabled()) {
            log.info("Demo mode: mail to @example.com and @parkease.dev addresses is dropped");
            return new DemoMailFilter(sender);
        }
        return sender;
    }

    private EmailSender realSender(AppMailProperties properties) {
        if (!StringUtils.hasText(properties.host())) {
            log.warn("MAIL_HOST not set — emails will be printed to the log instead of sent");
            return new LoggingEmailSender();
        }
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(properties.host());
        mailSender.setPort(properties.port());
        mailSender.setUsername(properties.username());
        mailSender.setPassword(properties.password());
        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "10000");
        return new SmtpEmailSender(mailSender, properties.from());
    }
}
