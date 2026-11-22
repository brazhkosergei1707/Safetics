package com.safetics.projects;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.safetics.projects.ProjectDtos.ProjectView;

/** Роль поточного користувача на проєкті за його призначенням і посадою. */
@Component
public class ProjectRoles {

    private final JdbcClient db;
    private final ProjectService projects;

    public ProjectRoles(JdbcClient db, ProjectService projects) {
        this.db = db;
        this.projects = projects;
    }

    /** Поточний користувач сьогодні призначений на проєкт на посаду з роллю supervisor. */
    public boolean isSupervisor(ProjectView project) {
        LocalDate today = projects.today(project);
        return Boolean.TRUE.equals(db.sql("""
                        select exists (
                            select 1
                            from personnel p
                                     join personnel_assignment a on a.personnel_id = p.id
                                     join position pos on pos.id = a.position_id
                            where p.user_id = app_user_id()
                              and a.project_id = ?
                              and pos.project_role = 'supervisor'
                              and a.start_date <= ?
                              and (a.end_date is null or a.end_date >= ?))
                        """)
                .params(project.id(), today, today).query(Boolean.class).single());
    }

    public boolean isSupervisor(UUID projectId) {
        return isSupervisor(projects.get(projectId));
    }
}
