package com.smartparking.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.common.seed.DemoMode;
import com.smartparking.support.RecordingEmailSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class DemoMailFilterTest {

    private final RecordingEmailSender real = new RecordingEmailSender();
    private final DemoMailFilter filter = new DemoMailFilter(real);

    private static EmailMessage to(String address) {
        return new EmailMessage(address, "Booking confirmed", "text", "<p>html</p>");
    }

    @Test
    void dropsMailToPlaceholderAndShowcaseAddresses(CapturedOutput output) {
        for (String address : new String[] {"ravi.kumar@example.com", "ANITA@EXAMPLE.COM", "driver@parkease.dev",
                "Owner.North@ParkEase.dev"}) {
            filter.send(to(address));
        }

        assertThat(real.sentTo("ravi.kumar@example.com")).isEmpty();
        assertThat(real.sentTo("ANITA@EXAMPLE.COM")).isEmpty();
        assertThat(real.sentTo("driver@parkease.dev")).isEmpty();
        assertThat(real.sentTo("Owner.North@ParkEase.dev")).isEmpty();
        assertThat(output.getAll()).contains("Demo mode: not sending").contains("Booking confirmed");
    }

    @Test
    void realVisitorsStillGetMail() {
        filter.send(to("real.person@gmail.com"));
        filter.send(to("someone@example.com.au"));
        filter.send(to("x@notexample.com"));

        assertThat(real.sentTo("real.person@gmail.com")).hasSize(1);
        assertThat(real.sentTo("someone@example.com.au")).hasSize(1);
        assertThat(real.sentTo("x@notexample.com")).hasSize(1);
    }

    @Test
    void theMailConfigWrapsTheSenderOnlyInDemoMode() {
        EmailConfig config = new EmailConfig();
        AppMailProperties noMail = new AppMailProperties("", 587, "", "", "ParkEase <no-reply@x>");

        assertThat(config.emailSender(noMail, new DemoMode(true))).isInstanceOf(DemoMailFilter.class);
        assertThat(config.emailSender(noMail, new DemoMode(false))).isInstanceOf(LoggingEmailSender.class);
    }
}
