package com.yubai.blog.common;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProjectArchiveService {
    private static final Logger log = LoggerFactory.getLogger(ProjectArchiveService.class);
    private final JdbcTemplate jdbc;

    public ProjectArchiveService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public void archive() {
        if (!hasTable("projects")) {
            log.info("projects table not found \u2013 archive skipped (V8+ or fresh install)");
            return;
        }
        log.info("projects table found \u2013 starting archive");
        createArchiveTables();
        copyProjects();
        copyProjectStack();
        log.info("archive completed successfully");
    }

    private boolean hasTable(String name) {
        var rows = jdbc.queryForList(
            "select table_name from information_schema.tables"
                + " where table_schema = current_schema and table_name = ?",
            String.class, name);
        return !rows.isEmpty();
    }

    private void createArchiveTables() {
        jdbc.execute("""
            create table if not exists projects_archived (
                id bigint not null primary key,
                title varchar(160) not null,
                description text not null,
                year varchar(10) not null,
                status varchar(40) not null,
                color varchar(20) not null,
                display_order integer not null,
                archived_at timestamp with time zone not null default now()
            )
        """);
        jdbc.execute("""
            create table if not exists project_stack_archived (
                project_id bigint not null,
                technology varchar(80) not null,
                sort_order integer not null,
                primary key (project_id, sort_order)
            )
        """);
    }

    private void copyProjects() {
        var inserted = jdbc.update("""
            insert into projects_archived (id, title, description, year, status, color, display_order, archived_at)
            select id, title, description, year, status, color, display_order, now() from projects
            on conflict (id) do nothing
        """);
        log.info("archived {} project row(s)", inserted);
    }

    private void copyProjectStack() {
        var inserted = jdbc.update("""
            insert into project_stack_archived (project_id, technology, sort_order)
            select project_id, technology, sort_order from project_stack
            on conflict (project_id, sort_order) do nothing
        """);
        log.info("archived {} project_stack row(s)", inserted);
    }
}
