package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Реестр авторизованных МОБИЛЬНЫХ УСТРОЙСТВ водителей — перенос legacy-справочника «Телефонҳо»
 * (phone_infos) из боевой платформы. Хранит, с какого устройства (бренд/модель) водитель
 * конкретной корхоны авторизовался в мобильном приложении.
 *
 * <p>Все ссылки «мягкие» (по РМА, без жёстких FK — как в прочих перенесённых таблицах):
 * {@code organizationRma} — РМА корхоны (обязательно), {@code driverRma} — табель/РМА водителя
 * (может отсутствовать, если устройство привязано к организации, а не к конкретному водителю),
 * {@code driverName} — ФИО водителя (денормализовано, как в legacy). {@code authorizedAt} —
 * дата/время авторизации устройства.</p>
 *
 * <p>Этап 1 — только сам реестр (ручной CRUD, {@link tj.mintrans.epd.masterdata.web.MobileDeviceController}).
 * Автозаполнение из мобильного приложения при авторизации — этап 2 (здесь не реализовано).</p>
 */
@Entity
@Table(name = "mobile_device")
public class MobileDevice {

    @Id
    private UUID id;

    @Column(name = "organization_rma", nullable = false)
    private String organizationRma;

    @Column(name = "driver_rma")
    private String driverRma;

    @Column(name = "driver_name", nullable = false)
    private String driverName;

    @Column(name = "brand")
    private String brand;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "authorized_at", nullable = false)
    private OffsetDateTime authorizedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (authorizedAt == null) authorizedAt = OffsetDateTime.now();
        if (createdAt == null) createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getOrganizationRma() { return organizationRma; }
    public void setOrganizationRma(String organizationRma) { this.organizationRma = organizationRma; }
    public String getDriverRma() { return driverRma; }
    public void setDriverRma(String driverRma) { this.driverRma = driverRma; }
    public String getDriverName() { return driverName; }
    public void setDriverName(String driverName) { this.driverName = driverName; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public OffsetDateTime getAuthorizedAt() { return authorizedAt; }
    public void setAuthorizedAt(OffsetDateTime authorizedAt) { this.authorizedAt = authorizedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
