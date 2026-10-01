package com.renyi.mes.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class InventoryCsvTests {
    @Test
    void quotesEmbeddedQuotesAndSeparators() {
        assertThat(InventoryExportWorker.csv("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(InventoryExportWorker.csv("a,b\nc")).isEqualTo("\"a,b\nc\"");
        assertThat(InventoryExportWorker.csv(null)).isEmpty();
    }

    @Test
    void neutralizesSpreadsheetFormulaPrefixes() {
        for (String value : new String[] {"=1+1", "+1", "-1", "@SUM(A1)", "  =1+1", "\t=1+1", "\r=1+1"}) {
            assertThat(InventoryExportWorker.csv(value)).startsWith("\"'");
        }
        assertThat(InventoryExportWorker.csv("CF8阀体")).isEqualTo("\"CF8阀体\"");
    }
}
