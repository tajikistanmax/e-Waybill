package tj.mintrans.epd.waybill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Запись журнала публичной проверки ПЛ по QR (§18 QA): факт обращения к
 * {@code GET /api/v1/verify/{jws}} — кто (IP/UA), когда, какой документ и с каким итогом.
 *
 * <p>Фиксируется КАЖДОЕ обращение, включая неуспешные (битая подпись, документ не найден),
 * чтобы надзор видел попытки проверки недействительных QR. Запись неизменяема — только
 * добавление. Из соображений минимизации ПДн хранится лишь то, что и так присутствует
 * в самом QR/ответе проверки (см. миграцию {@code V23__verify_scan_log.sql}).</p>
 */
@Entity
@Table(name = "verify_scan_log")
public class VerifyScanLog {

    /** Подпись верна, документ найден. */
    public static final String VALID = "VALID";
    /** Подпись/срок недействительны либо токен не разобран. */
    public static final String SIGNATURE_INVALID = "SIGNATURE_INVALID";
    /** Подпись верна, но документ по jti в системе не найден. */
    public static final String NOT_FOUND = "NOT_FOUND";

    @Id
    private UUID id;

    @Column(name = "scanned_at", nullable = false, updatable = false)
    private OffsetDateTime scannedAt;

    @Column(name = "jti")
    private String jti;

    @Column(name = "waybill_number")
    private String waybillNumber;

    @Column(nullable = false)
    private String result;

    @Column(name = "online_status")
    private String onlineStatus;

    @Column(name = "client_ip")
    private String clientIp;

    @Column(name = "user_agent")
    private String userAgent;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (scannedAt == null) scannedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public OffsetDateTime getScannedAt() { return scannedAt; }
    public String getJti() { return jti; }
    public void setJti(String jti) { this.jti = jti; }
    public String getWaybillNumber() { return waybillNumber; }
    public void setWaybillNumber(String waybillNumber) { this.waybillNumber = waybillNumber; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getOnlineStatus() { return onlineStatus; }
    public void setOnlineStatus(String onlineStatus) { this.onlineStatus = onlineStatus; }
    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
}
