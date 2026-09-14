package tj.mintrans.epd.waybill.web;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.service.WaybillService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Webhook платёжного шлюза: подтверждение оплаты без участия бухгалтера
 * (AWAITING_PAYMENT → PAID → READY). Эндпоинт открыт в SecurityConfig,
 * аутентификация — общий секрет в заголовке X-Payment-Secret
 * (PAYMENT_WEBHOOK_SECRET; в проде заменить и/или перейти на подпись тела HMAC).
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentWebhookController {

    // Известные committed/слабые дефолты секрета вебхука — недопустимы при включённой оплате.
    // ВАЖНО: держать в синхроне с дефолтами в infra/docker-compose*.yml и application.yml.
    private static final java.util.Set<String> WEAK_SECRETS = java.util.Set.of(
            "dev_webhook_secret", "epd_webhook_dev_secret");
    private static final int MIN_SECRET_LEN = 16;

    private final WaybillService service;
    private final String webhookSecret;
    private final boolean paymentEnabled;

    public PaymentWebhookController(WaybillService service,
                                    @Value("${epd.payment.enabled:false}") boolean paymentEnabled,
                                    @Value("${epd.payment.webhook-secret:dev_webhook_secret}") String webhookSecret) {
        this.service = service;
        this.paymentEnabled = paymentEnabled;
        this.webhookSecret = webhookSecret;
    }

    /** Прод-защита: при включённой оплате дефолтный/пустой секрет вебхука недопустим — иначе
     *  любой мог бы пометить ПЛ оплаченным. В dev (payment.enabled=false) — пропускается. */
    @PostConstruct
    void validateSecret() {
        if (paymentEnabled && (webhookSecret == null || webhookSecret.isBlank()
                || WEAK_SECRETS.contains(webhookSecret) || webhookSecret.length() < MIN_SECRET_LEN)) {
            throw new IllegalStateException(
                    "PAYMENT_WEBHOOK_SECRET не задан, дефолтный или короче " + MIN_SECRET_LEN
                    + " символов при включённой оплате — задайте сильный секрет.");
        }
    }

    public record WebhookRequest(
            @NotNull UUID waybillId,
            String method,        // GATEWAY | BANK | CASH; по умолчанию GATEWAY
            String externalRef) { // № транзакции шлюза
    }

    @PostMapping("/webhook")
    public ResponseEntity<Waybill> confirm(@RequestHeader(value = "X-Payment-Secret", required = false) String secret,
                                           @Valid @RequestBody WebhookRequest req) {
        if (secret == null || !constantTimeEquals(secret, webhookSecret)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        var wb = service.confirmPayment(req.waybillId(),
                req.method() == null ? "GATEWAY" : req.method(),
                req.externalRef(),
                "payment-gateway");
        return ResponseEntity.ok(wb);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
