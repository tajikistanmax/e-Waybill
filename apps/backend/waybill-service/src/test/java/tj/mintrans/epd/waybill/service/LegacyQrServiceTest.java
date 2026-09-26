package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tj.mintrans.epd.waybill.domain.Malumotnoma;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.repository.MalumotnomaRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Старые бумажные QR «Роҳхат» (сверка 25.09, G5): токен Laravel encrypt() → перенесённый лист MG{код}{id}. */
class LegacyQrServiceTest {

    private static final byte[] KEY = new byte[32];
    static {
        new SecureRandom().nextBytes(KEY);
    }
    private static final String APP_KEY = "base64:" + Base64.getEncoder().encodeToString(KEY);

    /** То же, что Laravel 8 {@code encrypt($value)}: serialize → AES-256-CBC → {iv, value, mac} → base64(JSON). */
    static String laravelEncrypt(String serialized, byte[] key) throws Exception {
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        String ivB = Base64.getEncoder().encodeToString(iv);
        String valueB = Base64.getEncoder().encodeToString(c.doFinal(serialized.getBytes(StandardCharsets.UTF_8)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        String macHex = HexFormat.of().formatHex(mac.doFinal((ivB + valueB).getBytes(StandardCharsets.UTF_8)));
        String payload = "{\"iv\":\"" + ivB + "\",\"value\":\"" + valueB + "\",\"mac\":\"" + macHex + "\",\"tag\":\"\"}";
        return Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private final MalumotnomaRepository malumotnomas = mock(MalumotnomaRepository.class);

    private LegacyQrService service(WaybillRepository repo, QrTokenService qr) {
        return new LegacyQrService(APP_KEY, repo, malumotnomas, qr);
    }

    @Test
    @DisplayName("расшифровка: i:123; и s:3:\"123\"; → 123; чужой ключ и мусор → пусто")
    void decrypt() throws Exception {
        var s = service(mock(WaybillRepository.class), mock(QrTokenService.class));
        assertThat(s.decrypt(laravelEncrypt("i:1340301;", KEY))).contains(1340301L);
        assertThat(s.decrypt(laravelEncrypt("s:3:\"123\";", KEY))).contains(123L);
        byte[] other = new byte[32];
        assertThat(s.decrypt(laravelEncrypt("i:5;", other))).isEmpty();
        assertThat(s.decrypt("not-a-token")).isEmpty();
    }

    @Test
    @DisplayName("тип 3 (3-С) → лист MG3C{id} → токен проверки")
    void resolve() throws Exception {
        WaybillRepository repo = mock(WaybillRepository.class);
        QrTokenService qr = mock(QrTokenService.class);
        Waybill wb = new Waybill();
        ReflectionTestUtils.setField(wb, "id", UUID.randomUUID());
        when(repo.findByNumber("MG3C1340301")).thenReturn(Optional.of(wb));
        when(qr.signLegacy(any(Waybill.class))).thenReturn("jws-token");
        assertThat(service(repo, qr).resolve("3", laravelEncrypt("i:1340301;", KEY))).isEqualTo("jws-token");
    }

    @Test
    @DisplayName("тип 8 (справка) → перенесённая справка с номером = legacy id → токен проверки справки")
    void resolveMalumotnoma() throws Exception {
        QrTokenService qr = mock(QrTokenService.class);
        Malumotnoma m = new Malumotnoma();
        ReflectionTestUtils.setField(m, "legacy", true);
        when(malumotnomas.findByNumber(53001L)).thenReturn(Optional.of(m));
        when(qr.sign(any(Malumotnoma.class))).thenReturn("jws-m");
        assertThat(service(mock(WaybillRepository.class), qr).resolve("8", laravelEncrypt("i:53001;", KEY)))
                .isEqualTo("jws-m");
        // Справка, выданная уже в e-Waybill (не архив), по старому QR не открывается.
        Malumotnoma fresh = new Malumotnoma();
        when(malumotnomas.findByNumber(53900L)).thenReturn(Optional.of(fresh));
        assertThatThrownBy(() -> service(mock(WaybillRepository.class), qr).resolve("8", laravelEncrypt("i:53900;", KEY)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("борхат/СМР (6–7), неперенесённые лист и справка — 404; без ключа — 422")
    void notFound() throws Exception {
        WaybillRepository repo = mock(WaybillRepository.class);
        when(repo.findByNumber(any())).thenReturn(Optional.empty());
        when(malumotnomas.findByNumber(any())).thenReturn(Optional.empty());
        var s = service(repo, mock(QrTokenService.class));
        assertThatThrownBy(() -> s.resolve("6", laravelEncrypt("i:1;", KEY))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> s.resolve("1", laravelEncrypt("i:1;", KEY))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> s.resolve("8", laravelEncrypt("i:1;", KEY))).isInstanceOf(NotFoundException.class);
        var noKey = new LegacyQrService("", repo, malumotnomas, mock(QrTokenService.class));
        assertThatThrownBy(() -> noKey.resolve("1", "x")).isInstanceOf(UnprocessableException.class);
    }
}
