package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.Malumotnoma;
import tj.mintrans.epd.waybill.domain.MalumotnomaLine;
import tj.mintrans.epd.waybill.domain.MalumotnomaRoute;
import tj.mintrans.epd.waybill.repository.MalumotnomaRepository;
import tj.mintrans.epd.waybill.repository.MalumotnomaRouteRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ForbiddenException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Маълумотнома (сверка 25.09, E5): сквозной номер, снимок цены строк, правка, аннулирование вместо удаления. */
class MalumotnomaServiceTest {

    private final MalumotnomaRepository repo = mock(MalumotnomaRepository.class);
    private final MalumotnomaRouteRepository routes = mock(MalumotnomaRouteRepository.class);
    private final CurrentUser user = mock(CurrentUser.class);
    private final TenantScope scope = mock(TenantScope.class);
    private final MalumotnomaService service = new MalumotnomaService(repo, routes, user, scope);

    private MalumotnomaRoute khujand;
    private MalumotnomaRoute bokhtar;

    private static MalumotnomaRoute route(String name, int car, int mbus, int bus, boolean active) {
        MalumotnomaRoute r = new MalumotnomaRoute();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        r.setName(name);
        r.setCarPrice(BigDecimal.valueOf(car));
        r.setMbusPrice(BigDecimal.valueOf(mbus));
        r.setBusPrice(BigDecimal.valueOf(bus));
        r.setActive(active);
        return r;
    }

    @BeforeEach
    void setUp() {
        khujand = route("ш. Душанбе - ш. Хуҷанд ", 190, 0, 0, true);
        bokhtar = route("ш. Душанбе - ш. Бохтар", 45, 30, 20, true);
        when(routes.findById(khujand.getId())).thenReturn(Optional.of(khujand));
        when(routes.findById(bokhtar.getId())).thenReturn(Optional.of(bokhtar));
        when(user.isTenantScoped()).thenReturn(false);
        when(user.organizationRma()).thenReturn(Optional.of("025680800"));
        when(user.rma()).thenReturn(Optional.of("990000001"));
        when(user.fullName()).thenReturn(Optional.of("Кассир Каримов"));
        when(repo.nextNumber()).thenReturn(53803L);
        when(repo.save(any(Malumotnoma.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("выдача: номер из последовательности, цена строки ×2 туда-обратно, порядок маршрутов, льгота ×0.5")
    void create() {
        Malumotnoma m = service.create(new MalumotnomaService.CreateRequest("  Ахмедов Сафар ", (short) 4, (short) 1,
                List.of(new MalumotnomaService.LineRequest(khujand.getId(), true),
                        new MalumotnomaService.LineRequest(bokhtar.getId(), false))));

        assertThat(m.getNumber()).isEqualTo(53803L);
        assertThat(m.getFio()).isEqualTo("Ахмедов Сафар");
        assertThat(m.getIssuerName()).isEqualTo("Кассир Каримов");
        assertThat(m.getLines()).extracting(MalumotnomaLine::getPosition).containsExactly((short) 0, (short) 1);
        assertThat(m.getLines()).extracting(l -> l.getPrice().intValue()).containsExactly(380, 45);
        assertThat(m.getPrice()).isEqualByComparingTo("212.50");                 // (380 + 45) × 0.5
        assertThat(m.getRouteSummary()).isEqualTo("ш. Душанбе - ш. Хуҷанд (сафари рафту баргашт), ш. Душанбе - ш. Бохтар");
    }

    @Test
    @DisplayName("отключённый маршрут в новую справку не берётся")
    void inactiveRoute() {
        MalumotnomaRoute off = route("старый", 10, 10, 10, false);
        when(routes.findById(off.getId())).thenReturn(Optional.of(off));
        assertThatThrownBy(() -> service.create(new MalumotnomaService.CreateRequest("Ф", (short) 4, (short) 0,
                List.of(new MalumotnomaService.LineRequest(off.getId(), false)))))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("отключён");
    }

    @Test
    @DisplayName("правка: вид/льгота/маршруты меняются, Ф.И.О. и номер — нет, «Изменил» фиксируется; архив и аннулированная — 422")
    void update() {
        Malumotnoma m = service.create(new MalumotnomaService.CreateRequest("Ахмедов", (short) 4, (short) 0,
                List.of(new MalumotnomaService.LineRequest(khujand.getId(), false))));
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(m, "id", id);
        when(repo.findById(id)).thenReturn(Optional.of(m));
        when(user.fullName()).thenReturn(Optional.of("Старший кассир"));

        Malumotnoma u = service.update(id, new MalumotnomaService.UpdateRequest((short) 1, (short) 0,
                List.of(new MalumotnomaService.LineRequest(bokhtar.getId(), true))));
        assertThat(u.getFio()).isEqualTo("Ахмедов");
        assertThat(u.getNumber()).isEqualTo(53803L);
        assertThat(u.getTransportTypeId()).isEqualTo((short) 1);
        assertThat(u.getLines()).hasSize(1);
        assertThat(u.getPrice()).isEqualByComparingTo("40.00");                  // автобус 20 × 2
        assertThat(u.getUpdaterName()).isEqualTo("Старший кассир");

        m.setAnnulledAt(java.time.OffsetDateTime.now());
        assertThatThrownBy(() -> service.update(id, new MalumotnomaService.UpdateRequest((short) 4, (short) 0,
                List.of(new MalumotnomaService.LineRequest(khujand.getId(), false)))))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("аннулирована");

        Malumotnoma archived = new Malumotnoma();
        ReflectionTestUtils.setField(archived, "legacy", true);
        UUID aid = UUID.randomUUID();
        when(repo.findById(aid)).thenReturn(Optional.of(archived));
        assertThatThrownBy(() -> service.update(aid, new MalumotnomaService.UpdateRequest((short) 4, (short) 0,
                List.of(new MalumotnomaService.LineRequest(khujand.getId(), false)))))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("Роҳхат");
    }

    @Test
    @DisplayName("аннулирование: кассиру — 403; администратору — только с причиной; повторно — 422")
    void annul() {
        Malumotnoma m = new Malumotnoma();
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(m));

        when(user.hasRole(anyString())).thenReturn(false);
        assertThatThrownBy(() -> service.annul(id, new MalumotnomaService.AnnulRequest("ошибка")))
                .isInstanceOf(ForbiddenException.class);

        when(user.hasRole("COMPANY_ADMIN")).thenReturn(true);
        assertThatThrownBy(() -> service.annul(id, new MalumotnomaService.AnnulRequest("  ")))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("причину");

        Malumotnoma a = service.annul(id, new MalumotnomaService.AnnulRequest("ошибка в Ф.И.О."));
        assertThat(a.isAnnulled()).isTrue();
        assertThat(a.getAnnulReason()).isEqualTo("ошибка в Ф.И.О.");
        assertThat(a.getAnnulledBy()).isEqualTo("Кассир Каримов");
        assertThatThrownBy(() -> service.annul(id, new MalumotnomaService.AnnulRequest("ещё раз")))
                .isInstanceOf(UnprocessableException.class);
    }

    @Test
    @DisplayName("маршрут, использованный в справках, не удаляется — только отключается")
    void deleteUsedRoute() {
        when(user.hasRole("SYSTEM_ADMIN")).thenReturn(true);
        when(routes.existsById(khujand.getId())).thenReturn(true);
        when(repo.countLinesByRoute(khujand.getId())).thenReturn(3L);
        assertThatThrownBy(() -> service.deleteRoute(khujand.getId()))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("отключите");
        verify(routes, never()).deleteById(any());
    }
}
