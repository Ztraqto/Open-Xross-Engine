package com.ztraqto.openxross.core.command;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HelpHomeListenerTest {
    @Test
    void twoPageNavigationUsesFourDistinctComponentIds() {
        List<String> ids = HelpHomeListener.paginationComponentIds(0, 2);

        assertEquals(4, new HashSet<>(ids).size());
        assertTrue(ids.get(1).contains("previous:"));
        assertTrue(ids.get(2).contains("next:"));
    }

    @Test
    void targetsAreClampedButSemanticIdsRemainDistinct() {
        List<String> ids = HelpHomeListener.paginationComponentIds(99, 2);

        assertEquals(4, new HashSet<>(ids).size());
        assertTrue(ids.get(0).endsWith("first:0"));
        assertTrue(ids.get(3).endsWith("last:1"));
    }
}
