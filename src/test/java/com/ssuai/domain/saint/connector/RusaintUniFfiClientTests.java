package com.ssuai.domain.saint.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

class RusaintUniFfiClientTests {

    private final RusaintUniFfiClient client = new RusaintUniFfiClient();

    @Test
    void mapsCustomEightAmSlotToNullWithoutShiftingExistingPeriods() throws Exception {
        assertThat(periodFromTimeRange("08:00-08:50")).isNull();
        assertThat(periodFromTimeRange("1 교시\n(08:00~08:50)")).isNull();

        assertThat(periodFromTimeRange("09:00-10:15")).isEqualTo(1);
        assertThat(periodFromTimeRange("09:00~10:15")).isEqualTo(1);
    }

    @Test
    void returnsNullWhenNoStartTimeCanBeFound() throws Exception {
        assertThat(periodFromTimeRange("")).isNull();
        assertThat(periodFromTimeRange("시간 미정")).isNull();
        assertThat(periodFromTimeRange("13:00-13:50")).isNull();
    }

    private Integer periodFromTimeRange(String timeRange) throws Exception {
        Method method = RusaintUniFfiClient.class.getDeclaredMethod("periodFromTimeRange", String.class);
        method.setAccessible(true);
        return (Integer) method.invoke(client, timeRange);
    }
}
