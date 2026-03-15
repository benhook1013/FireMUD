package net.firedevops.firemud.accountservice.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.accountservice.config.MailProperties;
import net.firedevops.firemud.accountservice.service.EmailService;
import net.firedevops.firemud.common.LoggingUtil;
import org.slf4j.Logger;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailServiceImpl implements EmailService {
  private static final Logger logger = LoggingUtil.getLogger(EmailServiceImpl.class);

  private final JavaMailSender mailSender;
  private final MailProperties mailProperties;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Dependencies are injected and not exposed")
  public EmailServiceImpl(JavaMailSender mailSender, MailProperties mailProperties) {
    this.mailSender = mailSender;
    this.mailProperties = mailProperties;
  }

  @Override
  @Timed(value = "email.send")
  public void sendEmail(String to, String subject, String body) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(mailProperties.getFrom());
    message.setTo(to);
    message.setSubject(subject);
    message.setText(body);
    logger.info("Sending email '{}' to {}", subject, to);
    mailSender.send(message);
  }
}
