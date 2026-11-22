package com.safetics.shifts;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.safetics.projects.ProjectDtos.ProjectView;
import com.safetics.projects.ProjectService;
import com.safetics.tasks.TaskDtos.TaskTypeView;
import com.safetics.tasks.TaskTypeService;
import com.safetics.tasks.TaskWorkflow;

/**
 * Задача «Бурова на проєкті» (тип з autoCreate = primary_assignment): одна на призначення основного
 * обладнання, відкрита, поки призначення діє. Створюється при призначенні (або планувальником, коли
 * настає дата початку), завершується, коли призначення закінчилось, скасовується при видаленні.
 */
@Service
public class RigTaskService {

    private record Assignment(UUID id, UUID projectId, UUID assetId, String assetCode, LocalDate startDate,
                              LocalDate endDate, boolean exclusive, UUID taskId) {
    }

    private final JdbcClient db;
    private final ProjectService projects;
    private final TaskTypeService types;
    private final TaskWorkflow tasks;

    public RigTaskService(JdbcClient db, ProjectService projects, TaskTypeService types, TaskWorkflow tasks) {
        this.db = db;
        this.projects = projects;
        this.types = types;
        this.tasks = tasks;
    }

    /** Створює задачу, якщо: основне обладнання, призначення діє сьогодні, задачі ще немає, тип є. */
    @Transactional
    public void ensureFor(UUID assignmentId) {
        Assignment a = find(assignmentId).orElse(null);
        if (a == null || !a.exclusive() || a.taskId() != null) {
            return;
        }
        ProjectView project = projects.get(a.projectId());
        LocalDate today = projects.today(project);
        if ("closed".equals(project.status()) || a.startDate().isAfter(today)
                || (a.endDate() != null && a.endDate().isBefore(today))) {
            return;
        }
        Optional<TaskTypeView> type = types.forPrimaryAssignment();
        if (type.isEmpty()) {
            return;
        }
        UUID taskId = tasks.createAssetTask(project, type.get(), a.assetId(), a.assetCode());
        db.sql("update equipment_assignment set task_id = ? where id = ?").params(taskId, a.id()).update();
    }

    /** Дати призначення змінились: закінчилось — задачу завершити, почалось — створити. */
    @Transactional
    public void syncFor(UUID assignmentId) {
        Assignment a = find(assignmentId).orElse(null);
        if (a == null) {
            return;
        }
        if (a.taskId() == null) {
            ensureFor(assignmentId);
            return;
        }
        LocalDate today = projects.today(projects.get(a.projectId()));
        if (a.endDate() != null && a.endDate().isBefore(today)) {
            tasks.moveMainTask(a.taskId(), "done", null);
        }
    }

    /** Призначення закінчено (закриття проєкту): задачу завершити, незалежно від дати. */
    @Transactional
    public void finishFor(UUID assignmentId) {
        Assignment a = find(assignmentId).orElse(null);
        if (a != null && a.taskId() != null) {
            tasks.moveMainTask(a.taskId(), "done", null);
        }
    }

    /** Перед видаленням призначення: задача скасовується, посилання знімається. */
    @Transactional
    public void releaseFor(UUID assignmentId) {
        Assignment a = find(assignmentId).orElse(null);
        if (a == null || a.taskId() == null) {
            return;
        }
        db.sql("update equipment_assignment set task_id = null where id = ?").param(a.id()).update();
        tasks.moveMainTask(a.taskId(), "cancelled", null);
    }

    /** Відкрита задача обладнання на проєкті на дату (для правил shift_created). */
    public Optional<UUID> taskFor(UUID projectId, UUID assetId, LocalDate date) {
        return db.sql("""
                        select task_id from equipment_assignment
                        where project_id = ? and asset_id = ? and task_id is not null
                          and start_date <= ? and (end_date is null or end_date >= ?)
                        order by start_date desc
                        limit 1
                        """)
                .params(projectId, assetId, date, date).query(UUID.class).optional();
    }

    private Optional<Assignment> find(UUID id) {
        return db.sql("""
                        select ea.id, ea.project_id, ea.asset_id, a.code, ea.start_date, ea.end_date, ea.exclusive, ea.task_id
                        from equipment_assignment ea join asset a on a.id = ea.asset_id
                        where ea.id = ?
                        """)
                .param(id)
                .query((rs, n) -> new Assignment(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                        rs.getObject("asset_id", UUID.class), rs.getString("code"),
                        rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                        rs.getBoolean("exclusive"), rs.getObject("task_id", UUID.class)))
                .optional();
    }
}
