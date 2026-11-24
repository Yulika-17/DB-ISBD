package com.db.config;

/** Роль узла в учебной топологии 1 ведущий + 1 ведомый. */
public enum NodeRole {
    STANDALONE,
    MASTER,
    REPLICA
}
