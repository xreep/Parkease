package com.smartparking.owner.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EarningsCsvTest {

    @Test
    void plainCellsAreLeftAlone() {
        assertThat(EarningsCsv.cell("Pune Spot")).isEqualTo("Pune Spot");
        assertThat(EarningsCsv.cell("")).isEmpty();
        assertThat(EarningsCsv.cell(null)).isEmpty();
        assertThat(EarningsCsv.cell("a-b")).isEqualTo("a-b");
    }

    @Test
    void cellsThatStartLikeAFormulaGetAnApostrophe() {
        for (String start : new String[] {"=", "+", "-", "@"}) {
            assertThat(EarningsCsv.cell(start + "1+1")).isEqualTo("'" + start + "1+1");
        }
        assertThat(EarningsCsv.cell("\t=1+1")).isEqualTo("'\t=1+1");
    }

    @Test
    void aLeadingCarriageReturnIsGuardedAndThenQuoted() {
        assertThat(EarningsCsv.cell("\r=1+1")).isEqualTo("\"'\r=1+1\"");
    }

    @Test
    void commasQuotesAndLineBreaksAreQuoted() {
        assertThat(EarningsCsv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(EarningsCsv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(EarningsCsv.cell("line1\nline2")).isEqualTo("\"line1\nline2\"");
    }
}
