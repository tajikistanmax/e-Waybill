package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

/** Клиент (мизоҷ): рақам, номгӯ, суроға, телефон. */
@Entity
@Table(name = "client")
public class Client {

    @Id
    private UUID id;

    /** РМА организации-владельца: клиенты ведутся по каждому перевозчику отдельно. */
    @Column(name = "organization_rma", nullable = false)
    private String organizationRma;

    private String number;

    @Column(nullable = false)
    private String name;

    private String address;

    private String phone;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
    }

    public UUID getId() { return id; }
    public String getOrganizationRma() { return organizationRma; }
    public void setOrganizationRma(String organizationRma) { this.organizationRma = organizationRma; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
}
