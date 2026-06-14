package com.acp.topology.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acp.topology.config.TopologyProperties;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.data.ValueWrapper;
import com.vesoft.nebula.client.graph.net.NebulaPool;
import com.vesoft.nebula.client.graph.net.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AC-26 (strengthened for CRIT-1): unit tests for the storaged-ONLINE wait logic in
 * {@link NebulaSchemaBootstrap}, exercised in ISOLATION with a mocked {@link Session} and a fake
 * clock/sleeper (no real waits, no Docker). These assert the <em>logic</em> the mockable layer can
 * verify — the readiness predicate is {@code Status == "ONLINE"} (never "a row exists"), {@code ADD
 * HOSTS} runs only when not ONLINE, {@code CREATE SPACE} is issued only AFTER ONLINE, and the polls
 * are deadline-bounded.
 *
 * <p><strong>False-confidence guard:</strong> a mocked session cannot reproduce the real
 * ADDed-but-OFFLINE storaged window or space propagation — only {@code ColdStartReadinessIT}
 * (Testcontainers, empty volumes) exercises the true timing that would have caught CRIT-1 live.
 */
class NebulaSchemaBootstrapUnitTest {

    private NebulaPool pool;
    private Session session;
    private TopologyProperties properties;
    private final List<String> executed = new ArrayList<>();
    private AtomicLong fakeNow;
    // Pre-built ResultSets (Mockito stubbing happens ONLY in setUp, never inside an answer lambda).
    private ResultSet offlineHosts;
    private ResultSet onlineHosts;
    private ResultSet okRs;
    private ResultSet failRs;

    @BeforeEach
    void setUp() throws Exception {
        pool = mock(NebulaPool.class);
        session = mock(Session.class);
        executed.clear();
        fakeNow = new AtomicLong(0L);

        lenient().when(pool.getSession(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(session);
        doNothing().when(session).release();

        offlineHosts = showHosts("nebula-storaged", "9779", "OFFLINE");
        onlineHosts = showHosts("nebula-storaged", "9779", "ONLINE");
        okRs = okResult();
        failRs = mock(ResultSet.class);
        lenient().when(failRs.isSucceeded()).thenReturn(false);
        lenient().when(failRs.getErrorMessage()).thenReturn("nope");

        properties = new TopologyProperties();
        properties.getNebula().setSpace("topology");
        properties.getNebula().setStoragedHost("nebula-storaged:9779");
        properties.getNebula().setPollIntervalMs(10);
        properties.getNebula().setStoragedOnlineDeadlineMs(1000);
        properties.getNebula().setSpaceUsableDeadlineMs(1000);
    }

    private NebulaSchemaBootstrap newBootstrap() {
        // Fake clock advances by 100ms on each sleep so deadline polls terminate deterministically.
        NebulaSchemaBootstrap.Sleeper sleeper = ms -> fakeNow.addAndGet(100);
        NebulaSchemaBootstrap.Clock clock = fakeNow::get;
        return new NebulaSchemaBootstrap(pool, properties, sleeper, clock);
    }

    /** A SHOW HOSTS ResultSet with one storaged row at the given status. */
    private ResultSet showHosts(String host, String port, String status) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        lenient().when(rs.isSucceeded()).thenReturn(true);
        lenient().when(rs.rowsSize()).thenReturn(1);
        // Build ValueWrappers FIRST (each does its own stubbing) so no stub is nested inside another.
        ValueWrapper hostVal = stringValue(host);
        ValueWrapper portVal = intLikeValue(port);
        ValueWrapper statusVal = stringValue(status);
        ResultSet.Record record = mock(ResultSet.Record.class);
        lenient().when(record.contains("Host")).thenReturn(true);
        lenient().when(record.contains("Port")).thenReturn(true);
        lenient().when(record.contains("Status")).thenReturn(true);
        lenient().when(record.get("Host")).thenReturn(hostVal);
        lenient().when(record.get("Port")).thenReturn(portVal);
        lenient().when(record.get("Status")).thenReturn(statusVal);
        lenient().when(rs.rowValues(0)).thenReturn(record);
        return rs;
    }

    private ValueWrapper stringValue(String s) throws Exception {
        ValueWrapper v = mock(ValueWrapper.class);
        lenient().when(v.isString()).thenReturn(true);
        lenient().when(v.asString()).thenReturn(s);
        lenient().when(v.toString()).thenReturn(s);
        return v;
    }

    private ValueWrapper intLikeValue(String literal) throws Exception {
        ValueWrapper v = mock(ValueWrapper.class);
        lenient().when(v.isString()).thenReturn(false);
        lenient().when(v.toString()).thenReturn(literal);
        return v;
    }

    private ResultSet okResult() {
        ResultSet rs = mock(ResultSet.class);
        lenient().when(rs.isSucceeded()).thenReturn(true);
        return rs;
    }

    @Test
    void readinessPredicateIsStatusOnlineNotRowExists() throws Exception {
        // A storaged host that is LISTED but OFFLINE must NOT be treated as ready (the old bug).
        when(session.execute(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            executed.add(q);
            return q.startsWith("SHOW HOSTS") ? offlineHosts : okRs;
        });

        // OFFLINE forever → polls exhaust the deadline → transient (so the runner retries).
        assertThatThrownBy(() -> newBootstrap().ensureStoragedOnline(session))
                .isInstanceOf(BootstrapTransientException.class)
                .hasMessageContaining("not ONLINE");
    }

    @Test
    void addHostsThenCreateSpaceOnlyAfterStoragedOnline() throws Exception {
        // OFFLINE on the first SHOW HOSTS, then ONLINE after the ADD HOSTS + one poll.
        AtomicLong showCalls = new AtomicLong(0);
        when(session.execute(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            executed.add(q);
            if (q.startsWith("SHOW HOSTS")) {
                long n = showCalls.incrementAndGet();
                return n <= 1 ? offlineHosts : onlineHosts;
            }
            return okRs;
        });

        newBootstrap().bootstrap();

        int addHostsIdx = indexOfFirst("ADD HOSTS");
        int createSpaceIdx = indexOfFirst("CREATE SPACE");
        assertThat(addHostsIdx).as("ADD HOSTS issued").isGreaterThanOrEqualTo(0);
        assertThat(createSpaceIdx).as("CREATE SPACE issued").isGreaterThan(addHostsIdx);
        // The host was polled to ONLINE (>=2 SHOW HOSTS) BEFORE CREATE SPACE.
        long showHostsBeforeCreate = executed.subList(0, createSpaceIdx).stream()
                .filter(q -> q.startsWith("SHOW HOSTS")).count();
        assertThat(showHostsBeforeCreate).as("polled SHOW HOSTS before CREATE SPACE")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void doesNotAddHostsWhenAlreadyOnline() throws Exception {
        when(session.execute(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            executed.add(q);
            return q.startsWith("SHOW HOSTS") ? onlineHosts : okRs;
        });

        newBootstrap().ensureStoragedOnline(session);

        assertThat(executed).noneMatch(q -> q.startsWith("ADD HOSTS"));
        verify(session, atLeastOnce()).execute("SHOW HOSTS;");
    }

    @Test
    void spaceNotUsableWithinDeadlineIsTransient() throws Exception {
        when(session.execute(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            return q.startsWith("USE ") ? failRs : okRs; // USE never succeeds
        });

        assertThatThrownBy(() -> newBootstrap().waitUntilSpaceUsable(session))
                .isInstanceOf(BootstrapTransientException.class)
                .hasMessageContaining("not usable");
    }

    @Test
    void noSessionAvailableIsTransientNotFatal() throws Exception {
        when(pool.getSession(anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenThrow(new RuntimeException("Connection refused"));

        assertThatThrownBy(() -> newBootstrap().bootstrap())
                .isInstanceOf(BootstrapTransientException.class);
        verify(session, never()).execute(anyString());
    }

    private int indexOfFirst(String prefix) {
        for (int i = 0; i < executed.size(); i++) {
            if (executed.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }
}
