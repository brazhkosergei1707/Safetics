package com.safetics.monthly;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.safetics.core.web.ApiException;

/** Закриті місяці проєкту: після затвердження місячного звіту рапорти місяця не змінюються. */
@Component
public class MonthCloses {

    private final JdbcClient db;

    public MonthCloses(JdbcClient db) {
        this.db = db;
    }

    public boolean isClosed(UUID projectId, LocalDate date) {
        return Boolean.TRUE.equals(db.sql("select exists (select 1 from project_month_close where project_id = ? and month = ?)")
                .params(projectId, date.withDayOfMonth(1)).query(Boolean.class).single());
    }

    /** 409 month_closed — виправлення лише коригувальним записом у наступному місяці. */
    public void requireOpen(UUID projectId, LocalDate date) {
        if (isClosed(projectId, date)) {
            throw ApiException.conflict("month_closed");
        }
    }
}
