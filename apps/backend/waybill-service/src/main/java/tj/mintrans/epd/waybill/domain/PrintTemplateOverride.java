package tj.mintrans.epd.waybill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Переопределение встроенного печатного шаблона (Thymeleaf {@code templates/print/<name>.html}) текстом из БД
 * (MIGRATION.md 7.1 / 10.4 — редактируемые бланки). Нет строки — печатается встроенный шаблон из jar.
 */
@Entity
@Table(name = "print_template_override")
public class PrintTemplateOverride {

    /** Имя шаблона без пути и расширения: waybill1ad, waybill3c, blocks, styles, cmr … */
    @Id
    private String name;

    @Column(nullable = false)
    private String content;

    private String note;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
