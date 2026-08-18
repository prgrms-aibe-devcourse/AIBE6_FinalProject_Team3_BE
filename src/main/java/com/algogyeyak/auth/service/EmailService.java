package com.algogyeyak.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 이메일 인증(회원가입)/비밀번호 재설정 메일 발송을 담당한다. 두 기능 모두 발송 실패를 fail-closed로
 * 처리한다 — 메일이 실제로 도착했는지 확신할 수 없는 상태에서 "성공"으로 응답하면, 사용자는 받지도
 * 못한 인증번호/링크를 기다리게 된다.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String fromAddress;

    // 이메일 본문의 "N분간 유효" 문구를 실제 TTL 설정값에서 계산한다 - 하드코딩하면 설정만 바뀌었을 때
    // 본문 문구가 조용히 틀려진다(EmailVerificationService/PasswordResetService가 같은 값으로 TTL을 건다).
    @Value("${app.email-verification.code-validity-seconds}")
    private long codeValiditySeconds;

    @Value("${app.password-reset.token-validity-seconds}")
    private long resetTokenValiditySeconds;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendVerificationCode(String toEmail, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject("[알고계약] 이메일 인증번호");
        message.setText("""
                회원가입을 위한 이메일 인증번호입니다.

                인증번호: %s

                인증번호는 발급 후 %s간 유효합니다. 본인이 요청하지 않았다면 이 메일을 무시해주세요.
                """.formatted(code, formatValidity(codeValiditySeconds)));
        send(message);
    }

    public void sendPasswordResetLink(String toEmail, String resetLink) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject("[알고계약] 비밀번호 재설정");
        message.setText("""
                아래 링크에서 새 비밀번호를 설정할 수 있습니다.

                %s

                이 링크는 발급 후 %s간 유효하며, 1회만 사용할 수 있습니다. 본인이 요청하지 않았다면
                이 메일을 무시해주세요 - 링크를 클릭하지 않으면 비밀번호는 변경되지 않습니다.
                """.formatted(resetLink, formatValidity(resetTokenValiditySeconds)));
        send(message);
    }

    // seconds/60 정수 나눗셈만 쓰면 60의 배수가 아닌 설정값(예: 90초→"1분", 30초→"0분")에서 문구가
    // 다시 부정확해진다 - 분/초로 나눠 표현해 어떤 TTL 설정값이 와도 정확하게 안내한다.
    private static String formatValidity(long totalSeconds) {
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes == 0) {
            return seconds + "초";
        }
        if (seconds == 0) {
            return minutes + "분";
        }
        return minutes + "분 " + seconds + "초";
    }

    private void send(SimpleMailMessage message) {
        try {
            mailSender.send(message);
        } catch (MailException e) {
            log.error("메일 발송 실패 to={}", message.getTo() != null ? message.getTo()[0] : null, e);
            throw e;
        }
    }
}
