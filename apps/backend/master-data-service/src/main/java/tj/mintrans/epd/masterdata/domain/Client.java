package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Клиент (мизоҷ): рақам, вид, номгӯ, суроға, телефон + банковские реквизиты (legacy {@code clients},
 * MIGRATION.md 2.24). Вид клиента {@code type}: 1 — мизоҷ (заказчик), 2 — борқабулкунанда
 * (грузополучатель), 3 — борфиристонанда (грузоотправитель), 4 — экспедитор.
 */
@Entity
@Table(name = "client")
public class Client {

    @Id
    private UUID id;

    /** РМА организации-владельца: клиенты ведутся по каждому перевозчику отдельно. */
    @Column(name = "organization_rma", nullable = false)
    private String organizationRma;

    private String number;

    /** Намуди мизоҷ: 1 заказчик, 2 грузополучатель, 3 грузоотправитель, 4 экспедитор (V66). */
    @Column(nullable = false)
    private short type = 1;

    @Column(nullable = false)
    private String name;

    private String address;

    private String phone;

    /** РЯМ (регистрационный номер юр. лица / ЕГРН). */
    private String riam;

    /** РМА (ИНН) клиента. */
    private String rma;

    /** Суратҳисоб — расчётный счёт. */
    private String account;

    /** Суратҳисоби мукотибавӣ — корреспондентский счёт. */
    @Column(name = "correspondence_account")
    private String correspondenceAccount;

    /** МФО банка. */
    private String mfo;

    /** Номгӯи бонк — наименование банка. */
    @Column(name = "bank_name")
    private String bankName;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
    }

    public UUID getId() { return id; }
    public short getType() { return type; }
    public void setType(short type) { this.type = type; }
    public String getRiam() { return riam; }
    public void setRiam(String riam) { this.riam = riam; }
    public String getRma() { return rma; }
    public void setRma(String rma) { this.rma = rma; }
    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
    public String getCorrespondenceAccount() { return correspondenceAccount; }
    public void setCorrespondenceAccount(String correspondenceAccount) { this.correspondenceAccount = correspondenceAccount; }
    public String getMfo() { return mfo; }
    public void setMfo(String mfo) { this.mfo = mfo; }
    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }
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
