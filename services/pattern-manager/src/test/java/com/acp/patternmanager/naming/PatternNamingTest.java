package com.acp.patternmanager.naming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link PatternNaming} — the deterministic, readable pattern-name derivation the
 * Pattern Manager owns. Covers the label map (mirrors web-ui {@code ALARM_TYPE_LABELS}), the middot
 * suffix format, distinctness of names for distinct patternIds sharing a root cause, and graceful
 * degradation on null/short id / unknown / null token.
 */
class PatternNamingTest {

    // --- label map (must mirror web-ui ALARM_TYPE_LABELS exactly) ---

    @ParameterizedTest
    @CsvSource({
        "AdjDown,Adjacency Down",
        "BGPPeerDown,BGP Peer Down",
        "ISISAdjacencyDown,IS-IS Adjacency Down",
        "OSPFAdjacencyDown,OSPF Adjacency Down",
        "RouteFlap,Route Flap",
        "LDPSessionDown,LDP Session Down",
        "LSPDown,LSP Down",
        "FRRSwitchover,FRR Switchover",
        "TETunnelDown,TE Tunnel Down",
        "LinkDown,Link Down",
        "IPLinkDown,IP Link Down",
        "FiberFault,Fiber Fault",
        "LOS,Loss of Signal",
        "LOF,Loss of Frame",
        "InterfaceDown,Interface Down",
        "PortDown,Port Down",
        "PortFlap,Port Flap",
    })
    void alarmTypeLabel_mapsEveryKnownToken(String token, String expected) {
        assertThat(PatternNaming.alarmTypeLabel(token)).isEqualTo(expected);
    }

    @Test
    void alarmTypeLabel_unknownToken_returnsRawTokenUnchanged() {
        assertThat(PatternNaming.alarmTypeLabel("SomeNewAlarm")).isEqualTo("SomeNewAlarm");
    }

    @Test
    void alarmTypeLabel_nullOrBlank_degradesToUnknownNeverBlank() {
        assertThat(PatternNaming.alarmTypeLabel(null)).isEqualTo("Unknown");
        assertThat(PatternNaming.alarmTypeLabel("")).isEqualTo("Unknown");
        assertThat(PatternNaming.alarmTypeLabel("   ")).isEqualTo("Unknown");
    }

    // --- patternName format ---

    @Test
    void patternName_buildsLabelCascadeMiddotShort8_forFullUuid() {
        String name = PatternNaming.patternName("IPLinkDown", "02007ff1-9d3a-5b7c-9d4e-1a2b3c4d5e6f");
        assertThat(name).isEqualTo("IP Link Down Cascade · 02007ff1");
    }

    @Test
    void patternName_separatorIsMiddotU00B7() {
        String name = PatternNaming.patternName("LOS", "abcd1234-0000-0000-0000-000000000000");
        assertThat(name).isEqualTo("Loss of Signal Cascade · abcd1234");
        assertThat(name).contains(" · ");
    }

    @Test
    void patternName_short8IsLowerCasedAndDashStripped() {
        String name = PatternNaming.patternName("PortDown", "AB-CD-EF-01-2345-6789");
        // first 8 hex after stripping dashes, lower-cased: abcdef01
        assertThat(name).isEqualTo("Port Down Cascade · abcdef01");
    }

    @Test
    void patternName_unknownToken_usesRawTokenInName() {
        String name = PatternNaming.patternName("NewFangledAlarm", "12345678-9abc-def0-1234-567890abcdef");
        assertThat(name).isEqualTo("NewFangledAlarm Cascade · 12345678");
    }

    // --- distinctness: same root cause, different patternId -> different name ---

    @Test
    void patternName_distinctPatternIds_sameRootCause_yieldDistinctNames() {
        String a = PatternNaming.patternName("IPLinkDown", "02007ff1-0000-0000-0000-000000000000");
        String b = PatternNaming.patternName("IPLinkDown", "9d3a1122-0000-0000-0000-000000000000");
        assertThat(a).isNotEqualTo(b);
        assertThat(a).isEqualTo("IP Link Down Cascade · 02007ff1");
        assertThat(b).isEqualTo("IP Link Down Cascade · 9d3a1122");
    }

    // --- graceful degradation on id ---

    @Test
    void patternName_nullId_omitsSuffixNoNullLiteral() {
        String name = PatternNaming.patternName("IPLinkDown", null);
        assertThat(name).isEqualTo("IP Link Down Cascade");
        assertThat(name).doesNotContain("null");
        assertThat(name).doesNotContain("·");
    }

    @Test
    void patternName_blankId_omitsSuffix() {
        assertThat(PatternNaming.patternName("IPLinkDown", "   "))
                .isEqualTo("IP Link Down Cascade");
    }

    @Test
    void patternName_tooShortAfterStrippingDashes_omitsSuffix() {
        // "ab-cd" -> "abcd" (4 hex) < 8 -> no suffix
        assertThat(PatternNaming.patternName("IPLinkDown", "ab-cd"))
                .isEqualTo("IP Link Down Cascade");
    }

    @Test
    void patternName_nullRootCauseAndNullId_degradesGracefully() {
        String name = PatternNaming.patternName(null, null);
        assertThat(name).isEqualTo("Unknown Cascade");
        assertThat(name).doesNotContain("null");
    }
}
